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

public class FinalTradeGrowthRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-04";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String[] SYMBOL_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"};
	private static final double FLOOR_PNL = 80249.81;
	private static final double FLOOR_WIN = 75.28;
	private static final int FLOOR_TRADES = 999;

	private interface Scenario {
		String name();
		default int maxOpenPositions() { return 3; }
		default int maxAggregateContracts() { return 50; }
		void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks);
	}

	private interface ScenarioApplier {
		void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks);
	}

	private static class SimpleScenario implements Scenario {
		private final String name;
		private final ScenarioApplier applier;
		private final int maxOpenPositions;
		private final int maxAggregateContracts;

		SimpleScenario(String name, ScenarioApplier applier) {
			this(name, applier, 3);
		}

		SimpleScenario(String name, ScenarioApplier applier, int maxOpenPositions) {
			this(name, applier, maxOpenPositions, 50);
		}

		SimpleScenario(String name, ScenarioApplier applier, int maxOpenPositions, int maxAggregateContracts) {
			this.name = name;
			this.applier = applier;
			this.maxOpenPositions = maxOpenPositions;
			this.maxAggregateContracts = maxAggregateContracts;
		}

		public String name() {
			return name;
		}

		public int maxOpenPositions() {
			return maxOpenPositions;
		}

		public int maxAggregateContracts() {
			return maxAggregateContracts;
		}

		public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
			applyBaseline(settings, risks);
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
		int maxRuns = args.length > 1 ? Math.max(0, parseInt(args[1], 0)) : 0;
		boolean verbose = args.length > 2 && "verbose".equalsIgnoreCase(args[2].trim());
		if ("promote_final_live".equals(filter)) {
			promoteFinalLiveStack();
			return;
		}
		Map<String, FuturesManager.FuturesStrategySettings> baseSettings = loadBaseSettings();
		Map<String, FuturesManager.FuturesRiskSettings> baseRisks = loadBaseRisks();
		List<RunSummary> summaries = new ArrayList<RunSummary>();
		int executed = 0;
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
					scenario.maxOpenPositions(),
					scenario.maxAggregateContracts(),
					5.0,
					true,
					0.0,
					FUNDED_PROFILE
				);
				RunSummary summary = loadRun(id, scenario.name());
				saveRunLabel(id, scenario.name());
				summaries.add(summary);
				if (verbose || passesGoal(summary)) {
					System.out.println(line(summary));
				}
				executed++;
				if (maxRuns > 0 && executed >= maxRuns) {
					break;
				}
			}
		} finally {
			restore(baseSettings, baseRisks);
		}
		printRankings(summaries, verbose, executed);
	}

	private static List<Scenario> scenarios() {
		List<Scenario> values = new ArrayList<Scenario>();
		values.add(new SimpleScenario("goal_baseline_floor", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		}));

		addMicroShadowScenarios(values);
		addTrendRefillScenarios(values);
		addRangeCompressionScenarios(values);
		addValueAreaScenarios(values);
		addValueAreaCompressionBlends(values);
		addLooseProbeScenarios(values);
		addSecondChanceOrbScenarios(values);
		addLateCutoffOrbScenarios(values);
		addComposedCandidateScenarios(values);
		return values;
	}

	private static void promoteFinalLiveStack() throws Exception {
		Map<String, FuturesManager.FuturesStrategySettings> settings = loadBaseSettings();
		Map<String, FuturesManager.FuturesRiskSettings> risks = loadBaseRisks();
		applyBaseline(settings, risks);
		applyCurrentBestLongSecondWaveStack(settings);
		addThirdWavePrunedOmomCells(settings);
		save(settings, risks);
		System.out.println("PROMOTED_BACKTEST_SLOT sourcePortfolioBacktestID=3154 symbols=" + SYMBOLS);
		System.out.println(FuturesManager.copyBacktestStrategyToLive(SYMBOLS));
		System.out.println(FuturesManager.updateLiveStrategySnapshotFromPortfolioRun(3154));
	}

	private static void addMicroShadowScenarios(List<Scenario> values) {
		final String[] modes = new String[] {"mnq", "mes", "both"};
		final int[] caps = new int[] {2, 4, 6, 8};
		final int[] buckets = new int[] {10, 15, 20, 30};
		final double[] volumes = new double[] {0.35, 0.50, 0.65, 0.80};
		final double[] rewards = new double[] {0.65, 0.80, 1.00};
		final double[] risks = new double[] {8.0, 12.0, 16.0};
		for (final String mode : modes) {
			for (final int cap : caps) {
				for (final int bucket : buckets) {
					for (final double volume : volumes) {
						for (final double reward : rewards) {
							for (final double risk : risks) {
								values.add(new SimpleScenario("shadow_" + mode + "_cap" + cap + "_b" + bucket + "_v" + tag(volume) + "_rr" + tag(reward) + "_r" + tag(risk), new ScenarioApplier() {
									public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
										if ("mnq".equals(mode) || "both".equals(mode)) {
											configureMicroShadow(settings.get("MNQ"), cap, bucket, volume, reward, risk, 0.0, 570, 930, true, true);
										}
										if ("mes".equals(mode) || "both".equals(mode)) {
											configureMicroShadow(settings.get("MES"), cap, bucket, volume, reward, risk, 0.0, 570, 930, true, true);
										}
									}
								}));
							}
						}
					}
				}
			}
		}
	}

	private static void addTrendRefillScenarios(List<Scenario> values) {
		final String[] symbols = new String[] {"MNQ", "MES", "NQ", "ES"};
		final int[] caps = new int[] {2, 4, 6, 8};
		final int[] buckets = new int[] {8, 12, 16, 24};
		final double[] volumes = new double[] {0.35, 0.50, 0.65, 0.80};
		final double[] rewards = new double[] {0.55, 0.65, 0.75, 0.90};
		for (final String symbol : symbols) {
			for (final int cap : caps) {
				for (final int bucket : buckets) {
					for (final double volume : volumes) {
						for (final double reward : rewards) {
							values.add(new SimpleScenario("tlad_refill_" + symbol.toLowerCase() + "_cap" + cap + "_b" + bucket + "_v" + tag(volume) + "_rr" + tag(reward), new ScenarioApplier() {
								public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
									configureTrendLadder(settings.get(symbol), cap, bucket, volume, 14.0, reward, 4.0, 0.35, 600, 900, 8, false);
								}
							}));
						}
					}
				}
			}
		}
	}

	private static void addRangeCompressionScenarios(List<Scenario> values) {
		final String[] symbols = new String[] {"MNQ", "MES", "NQ", "ES", "MGC", "M2K"};
		final int[] caps = new int[] {2, 4};
		final int[] buckets = new int[] {8, 12, 20};
		final int[] boxBars = new int[] {4, 5, 6};
		final double[] volumes = new double[] {0.50, 0.75, 1.00};
		final double[] rewards = new double[] {0.55, 0.75};
		final double[] risks = new double[] {10.0, 16.0, 22.0};
		for (final String symbol : symbols) {
			for (final int cap : caps) {
				for (final int bucket : buckets) {
					for (final int bars : boxBars) {
						for (final double volume : volumes) {
							for (final double reward : rewards) {
								for (final double risk : risks) {
									values.add(new SimpleScenario("rcb_" + symbol.toLowerCase() + "_cap" + cap + "_b" + bucket + "_box" + bars + "_v" + tag(volume) + "_rr" + tag(reward) + "_r" + tag(risk), new ScenarioApplier() {
										public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
											configureRangeCompression(settings.get(symbol), cap, bucket, bars, volume, reward, risk, 0.25, 600, 915, true, true);
										}
									}));
								}
							}
						}
					}
				}
			}
		}
	}

	private static void addValueAreaScenarios(List<Scenario> values) {
		final String[] symbols = new String[] {"MNQ", "MES", "NQ", "ES", "MGC", "M2K"};
		final int[] caps = new int[] {2, 3, 4};
		final int[] buckets = new int[] {30, 45, 60};
		final double[] profilePcts = new double[] {0.68, 0.70};
		final double[] binTicks = new double[] {2.0, 4.0, 8.0};
		final double[] reclaims = new double[] {2.0, 4.0, 6.0};
		final double[] volumes = new double[] {0.45, 0.65, 0.85};
		final double[] rewards = new double[] {0.65, 0.85, 1.05};
		final double[] risks = new double[] {18.0, 30.0, 42.0};
		for (final String symbol : symbols) {
			for (final int cap : caps) {
				for (final int bucket : buckets) {
					for (final double pct : profilePcts) {
						for (final double bin : binTicks) {
							for (final double reclaim : reclaims) {
								for (final double volume : volumes) {
									for (final double reward : rewards) {
										for (final double risk : risks) {
											values.add(new SimpleScenario("vpb_" + symbol.toLowerCase() + "_cap" + cap + "_b" + bucket + "_pct" + tag(pct) + "_bin" + tag(bin) + "_rec" + tag(reclaim) + "_v" + tag(volume) + "_rr" + tag(reward) + "_r" + tag(risk), new ScenarioApplier() {
												public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
													configureValueArea(settings.get(symbol), cap, bucket, pct, bin, reclaim, volume, reward, risk, 0.10, 585, 900, true, true);
												}
											}));
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private static void addValueAreaCompressionBlends(List<Scenario> values) {
		final String[] symbols = new String[] {"MNQ", "MES", "NQ", "MGC"};
		final int[] caps = new int[] {2, 3};
		final double[] volumes = new double[] {0.45, 0.65, 0.85};
		final double[] rewards = new double[] {0.65, 0.85};
		for (final String symbol : symbols) {
			for (final int cap : caps) {
				for (final double volume : volumes) {
					for (final double reward : rewards) {
						values.add(new SimpleScenario("blend_vpb_rcb_" + symbol.toLowerCase() + "_cap" + cap + "_v" + tag(volume) + "_rr" + tag(reward), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
								FuturesManager.FuturesStrategySettings symbolSettings = settings.get(symbol);
								configureValueArea(symbolSettings, cap, 45, 0.70, 4.0, 3.0, volume, reward, 30.0, 0.10, 585, 900, true, true);
								configureRangeCompression(symbolSettings, cap, 12, 5, Math.max(0.65, volume), reward, 16.0, 0.25, 600, 915, true, true);
							}
						}));
					}
				}
			}
		}
	}

	private static void addLooseProbeScenarios(List<Scenario> values) {
		final String[] symbols = new String[] {"MNQ", "M2K", "MES", "NQ"};
		final int[] caps = new int[] {1, 2, 4};
		final double[] rewards = new double[] {0.45, 0.55, 0.70};
		for (final String symbol : symbols) {
			for (final int cap : caps) {
				for (final double reward : rewards) {
					values.add(new SimpleScenario("probe_rcb_" + symbol.toLowerCase() + "_cap" + cap + "_rr" + tag(reward), new ScenarioApplier() {
						public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
							configureLooseRangeCompression(settings.get(symbol), cap, reward);
						}
					}));
					values.add(new SimpleScenario("probe_vpb_" + symbol.toLowerCase() + "_cap" + cap + "_rr" + tag(reward), new ScenarioApplier() {
						public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
							configureLooseValueArea(settings.get(symbol), cap, reward);
						}
					}));
					values.add(new SimpleScenario("probe_dual_" + symbol.toLowerCase() + "_cap" + cap + "_rr" + tag(reward), new ScenarioApplier() {
						public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
							FuturesManager.FuturesStrategySettings symbolSettings = settings.get(symbol);
							configureLooseRangeCompression(symbolSettings, cap, reward);
							configureLooseValueArea(symbolSettings, cap, reward);
						}
					}));
				}
			}
		}
	}

	private static void addSecondChanceOrbScenarios(List<Scenario> values) {
		final String[] symbols = new String[] {"MNQ", "MES", "NQ", "ES", "MGC"};
		final double[] compressedRisks = new double[] {24.0, 32.0, 40.0};
		for (final String symbol : symbols) {
			for (final double risk : compressedRisks) {
				values.add(new SimpleScenario("orb_compress_" + symbol.toLowerCase() + "_r" + tag(risk), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
						configureCompressedOrb(settings.get(symbol), risk);
					}
				}));
				values.add(new SimpleScenario("orb_retest_" + symbol.toLowerCase() + "_r" + tag(risk), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
						configureOrbRetest(settings.get(symbol), risk);
					}
				}));
				values.add(new SimpleScenario("orb_secondchance_" + symbol.toLowerCase() + "_r" + tag(risk), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
						FuturesManager.FuturesStrategySettings symbolSettings = settings.get(symbol);
						configureCompressedOrb(symbolSettings, risk);
						configureOrbRetest(symbolSettings, risk);
					}
				}));
			}
		}
	}

	private static void addLateCutoffOrbScenarios(List<Scenario> values) {
		final double[] risks = new double[] {24.0, 32.0, 40.0};
		for (final double risk : risks) {
			values.add(new SimpleScenario("orb_late_nq_short_r" + tag(risk), new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
					configureCompressedOrb(nq, risk);
					nq.allowOrbLongs = false;
					nq.allowOrbShorts = true;
					nq.orbShortSkipStartMinute = 570;
					nq.orbShortSkipEndMinute = 659;
				}
			}));
		}
	}

	private static void addComposedCandidateScenarios(List<Scenario> values) {
		values.add(new SimpleScenario("compose_lorb_nq32", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				configureLateOrbContinuation(settings.get("NQ"), 32.0);
			}
		}));
		for (final double volume : new double[] {0.90, 1.00, 1.10, 1.30, 1.50}) {
			values.add(new SimpleScenario("compose_lorb_nq32_v" + tag(volume), new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					configureLateOrbContinuation(settings.get("NQ"), 32.0, volume);
				}
			}));
		}
		for (final int endMinute : new int[] {665, 670}) {
			values.add(new SimpleScenario("compose_lorb_nq32_v110_e" + endMinute, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					configureLateOrbContinuation(settings.get("NQ"), 32.0, 1.10);
					settings.get("NQ").lateOrbContinuationEndMinute = endMinute;
				}
			}));
		}
		values.add(new SimpleScenario("compose_lorb_nq32_mes_shadow", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				configureLateOrbContinuation(settings.get("NQ"), 32.0);
				configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, true, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_nq32_mes_short_echo", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				configureLateOrbContinuation(settings.get("NQ"), 32.0);
				configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
			}
		}));
		for (final int endMinute : new int[] {665, 670}) {
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_mes_short_echo", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					configureLateOrbContinuation(settings.get("NQ"), 32.0, 1.10);
					settings.get("NQ").lateOrbContinuationEndMinute = endMinute;
					configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_mes_echo_m2k_late", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					configureLateOrbContinuation(settings.get("NQ"), 32.0, 1.10);
					settings.get("NQ").lateOrbContinuationEndMinute = endMinute;
					configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
					enableCloseMomentumRefills(settings);
					settings.get("M2K").closeMomentumShortStartMinute = 889;
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_mnq_lorb_micro_stack", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					configureLateOrbContinuation(settings.get("NQ"), 32.0, 1.10);
					settings.get("NQ").lateOrbContinuationEndMinute = endMinute;
					configureLateOrbContinuation(settings.get("MNQ"), 32.0, 1.10);
					settings.get("MNQ").lateOrbContinuationEndMinute = endMinute;
					configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
					enableCloseMomentumRefills(settings);
					settings.get("M2K").closeMomentumShortStartMinute = 889;
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_close_refill", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					configureLateOrbContinuation(settings.get("NQ"), 32.0, 1.10);
					settings.get("NQ").lateOrbContinuationEndMinute = endMinute;
					configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
					enableCloseMomentumRefills(settings);
					settings.get("M2K").closeMomentumShortStartMinute = 889;
					FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
					mgc.closeMomentum.enabled = true;
					mgc.closeMomentum.maxTradesPerDay = 3;
					mgc.allowCloseMomentumLongs = false;
					mgc.allowCloseMomentumShorts = true;
					mgc.closeMomentumShortStartMinute = 865;
					mgc.closeMomentumMinMoveTicks = 16.0;
					mgc.closeMomentumVolumeRatio = 0.5;
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_mes_cmom_only", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					configureCloseMomentum(settings.get("MES"), 5, false, true, 850, 14.0, 0.45, 0.75);
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_es_lorb", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					configureLateOrbContinuation(settings.get("NQ"), 32.0, 1.10);
					settings.get("NQ").lateOrbContinuationEndMinute = endMinute;
					configureLateOrbContinuation(settings.get("ES"), 24.0, 1.10);
					settings.get("ES").lateOrbContinuationEndMinute = endMinute;
					configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
					enableCloseMomentumRefills(settings);
					settings.get("M2K").closeMomentumShortStartMinute = 889;
					FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
					mgc.closeMomentum.enabled = true;
					mgc.closeMomentum.maxTradesPerDay = 3;
					mgc.allowCloseMomentumLongs = false;
					mgc.allowCloseMomentumShorts = true;
					mgc.closeMomentumShortStartMinute = 865;
					mgc.closeMomentumMinMoveTicks = 16.0;
					mgc.closeMomentumVolumeRatio = 0.5;
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_krev_mes_mgc", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					configureKeltnerReversionTuned(settings.get("MES"), 4, 12.0, 0.85, 1.45, 0.70, 18.0, 8.0, 12);
					configureKeltnerReversionTuned(settings.get("MGC"), 4, 18.0, 0.85, 1.45, 0.70, 18.0, 8.0, 12);
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_krev_micro_basket", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					configureKeltnerReversionTuned(settings.get("MES"), 4, 12.0, 0.85, 1.45, 0.70, 18.0, 8.0, 12);
					configureKeltnerReversionTuned(settings.get("MGC"), 4, 18.0, 0.85, 1.45, 0.70, 18.0, 8.0, 12);
					configureKeltnerReversionTuned(settings.get("M2K"), 4, 18.0, 0.85, 1.45, 0.70, 18.0, 8.0, 12);
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_cmom_index_expansion", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					configureCloseMomentum(settings.get("MNQ"), 4, false, true, 865, 18.0, 0.50, 0.75);
					configureCloseMomentum(settings.get("NQ"), 2, false, true, 865, 20.0, 0.55, 0.80);
					configureCloseMomentum(settings.get("ES"), 2, false, true, 865, 18.0, 0.55, 0.80);
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_cmom_micro_expansion", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					configureCloseMomentum(settings.get("MES"), 5, false, true, 850, 14.0, 0.45, 0.75);
					configureCloseMomentum(settings.get("M2K"), 5, false, true, 850, 14.0, 0.45, 0.75);
					configureCloseMomentum(settings.get("MGC"), 5, false, true, 850, 14.0, 0.45, 0.75);
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_mscalp_mnq_mes", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					configureMicroScalp(settings.get("MNQ"), 6, 20, 0.65, 0.55, 10.0, 0.75, false, true);
					configureMicroScalp(settings.get("MES"), 6, 20, 0.65, 0.55, 8.0, 0.75, false, true);
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_mscalp_short_basket", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					configureMicroScalp(settings.get("MNQ"), 6, 20, 0.65, 0.55, 10.0, 0.75, false, true);
					configureMicroScalp(settings.get("MES"), 6, 20, 0.65, 0.55, 8.0, 0.75, false, true);
					configureMicroScalp(settings.get("MGC"), 6, 20, 0.65, 0.55, 10.0, 0.75, false, true);
					configureMicroScalp(settings.get("M2K"), 6, 20, 0.65, 0.55, 10.0, 0.75, false, true);
				}
			}));
		}
		for (final int endMinute : new int[] {671, 672, 673, 674, 675}) {
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_close_refill_v110", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_close_refill_v120", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					settings.get("NQ").lateOrbContinuationMinVolumeRatio = 1.20;
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e" + endMinute + "_gold_mscalp_mnq_mes_v110", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyGoldCloseStack(settings, endMinute);
					configureMicroScalp(settings.get("MNQ"), 6, 20, 0.65, 0.55, 10.0, 0.75, false, true);
					configureMicroScalp(settings.get("MES"), 6, 20, 0.65, 0.55, 8.0, 0.75, false, true);
				}
			}));
		}
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_afternoon", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 8, 20, 0.65, 0.55, 8.0, 0.75, false, true, 840, 920, 0, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_skip13", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 8, 20, 0.65, 0.55, 8.0, 0.75, false, true, 720, 920, 780, 839);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_dense_afternoon", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 12, 10, 0.60, 0.55, 8.0, 0.75, false, true, 840, 920, 0, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_dense_skip13", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 12, 10, 0.60, 0.55, 8.0, 0.75, false, true, 720, 920, 780, 839);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_dense_rr35_skip13", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 16, 10, 0.60, 0.35, 8.0, 0.75, false, true, 720, 920, 780, 839);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_dense_rr45_skip13", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 16, 10, 0.60, 0.45, 8.0, 0.75, false, true, 720, 920, 780, 839);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_dense_rr35_all", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 20, 10, 0.60, 0.35, 8.0, 0.75, false, true, 590, 920, 0, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_dense_rr45_all", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 20, 10, 0.60, 0.45, 8.0, 0.75, false, true, 590, 920, 0, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_mgc_pruned", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 10, 20, 0.65, 0.55, 8.0, 0.75, false, true, 590, 920, 0, 0, 20);
				configureMicroScalp(settings.get("MGC"), 8, 20, 0.65, 0.55, 10.0, 0.75, false, true, 590, 920, 0, 0, 22);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_mgc_pruned_dense", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 16, 10, 0.60, 0.55, 8.0, 0.75, false, true, 590, 920, 0, 0, 20);
				configureMicroScalp(settings.get("MGC"), 12, 10, 0.60, 0.55, 10.0, 0.75, false, true, 590, 920, 0, 0, 22);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_mgc_pruned_dense_open4", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 16, 10, 0.60, 0.55, 8.0, 0.75, false, true, 590, 920, 0, 0, 20);
				configureMicroScalp(settings.get("MGC"), 12, 10, 0.60, 0.55, 10.0, 0.75, false, true, 590, 920, 0, 0, 22);
			}
		}, 4));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_mgc_pruned_dense_open5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
			}
		}, 5));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_omom_short660", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumShortEndMinute = 660;
				mnq.openingMomentumShortSkipStartMinute = 0;
				mnq.openingMomentumShortSkipEndMinute = 0;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_omom_short660_rr50", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumShortEndMinute = 660;
				mnq.openingMomentumShortSkipStartMinute = 0;
				mnq.openingMomentumShortSkipEndMinute = 0;
				mnq.openingMomentumRewardRisk = 0.50;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_omom_long_unskip", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumLongSkipStartMinute = 0;
				mnq.openingMomentumLongSkipEndMinute = 0;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_omom_wide_rr50", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.openingMomentumShortEndMinute = 660;
				mnq.openingMomentumShortSkipStartMinute = 0;
				mnq.openingMomentumShortSkipEndMinute = 0;
				mnq.openingMomentumLongSkipStartMinute = 0;
				mnq.openingMomentumLongSkipEndMinute = 0;
				mnq.openingMomentumRewardRisk = 0.50;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_mes_omom_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_mgc_omom_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_micro_omom_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 0);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 0);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_micro_omom_short_mwf", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 20);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 20);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 20);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_mes_omom_tuewed", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 50);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_m2k_omom_tuethu", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 42);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_mes_m2k_omom_clean", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 50);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 42);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_mes_m2k_omom_clean_lowrisk", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 50);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 42);
				settings.get("MES").openingMomentumPortfolioRiskMultiplier = 0.20;
				settings.get("M2K").openingMomentumPortfolioRiskMultiplier = 0.20;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mes_tue_omom", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_thu_omom", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 645, 649, 46);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tue_omom", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_omom_alt", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
				configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mes_m2k_tuethu_omom_alt", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
				configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
			}
		}));
		for (final double mesRiskTicks : new double[] {32.0, 28.0, 24.0, 20.0, 16.0}) {
			values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_risk" + Math.round(mesRiskTicks), new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyPrunedDenseMicroStack(settings);
					configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
					configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
					configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
					settings.get("MES").openingMomentumMaxRiskTicks = mesRiskTicks;
				}
			}));
		}
		for (final double mesMaxRsi : new double[] {50.0, 48.0, 45.0, 42.0}) {
			values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_risk32_rsi" + Math.round(mesMaxRsi), new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyPrunedDenseMicroStack(settings);
					configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
					configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
					configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
					settings.get("MES").openingMomentumMaxRiskTicks = 32.0;
					settings.get("MES").openingMomentumShortMaxRsi = mesMaxRsi;
				}
			}));
		}
		for (final int mesSkipDowMask : new int[] {0, 20, 50, 58}) {
			values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_wide_mask" + mesSkipDowMask + "_risk32_rsi50", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyPrunedDenseMicroStack(settings);
					configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
					configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
					configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, mesSkipDowMask);
					settings.get("MES").openingMomentumMaxRiskTicks = 32.0;
					settings.get("MES").openingMomentumShortMaxRsi = 50.0;
				}
			}));
		}
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tue620_thu_mes_risk32_rsi50", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 629, 58);
				configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
				settings.get("MES").openingMomentumMaxRiskTicks = 32.0;
				settings.get("MES").openingMomentumShortMaxRsi = 50.0;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_rsi50_cmom_m2k_long", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRsi50Stack(settings);
				settings.get("M2K").allowCloseMomentumLongs = true;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_rsi50_cmom_mes_long", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRsi50Stack(settings);
				settings.get("MES").allowCloseMomentumLongs = true;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_rsi50_cmom_mgc_long", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRsi50Stack(settings);
				settings.get("MGC").allowCloseMomentumLongs = true;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_rsi50_cmom_mgc_long_900_909", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRsi50Stack(settings);
				settings.get("MGC").allowCloseMomentumLongs = true;
				settings.get("MGC").closeMomentumLongStartMinute = 900;
				settings.get("MGC").closeMomentumLongEndMinute = 909;
			}
		}));
		for (final int mgcLongEnd : new int[] {914, 919, 925}) {
			values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_rsi50_cmom_mgc_long_900_" + mgcLongEnd, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applySurgicalM2kTueThuMesRsi50Stack(settings);
					settings.get("MGC").allowCloseMomentumLongs = true;
					settings.get("MGC").closeMomentumLongStartMinute = 900;
					settings.get("MGC").closeMomentumLongEndMinute = mgcLongEnd;
				}
			}));
		}
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mgc_cmom900_mscalp_long880", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRsi50MgcLongStack(settings, 909);
				settings.get("MGC").allowMicroScalpLongs = true;
				settings.get("MGC").microScalpLongStartMinute = 880;
				settings.get("MGC").microScalpLongEndMinute = 889;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mgc_cmom900_mscalp_long885", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRsi50MgcLongStack(settings, 909);
				settings.get("MGC").allowMicroScalpLongs = true;
				settings.get("MGC").microScalpLongStartMinute = 885;
				settings.get("MGC").microScalpLongEndMinute = 889;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mgc_cmom925_mscalp_long880", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRsi50MgcLongStack(settings, 925);
				settings.get("MGC").allowMicroScalpLongs = true;
				settings.get("MGC").microScalpLongStartMinute = 880;
				settings.get("MGC").microScalpLongEndMinute = 889;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mgc_cmom925_mscalp_long885", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRsi50MgcLongStack(settings, 925);
				settings.get("MGC").allowMicroScalpLongs = true;
				settings.get("MGC").microScalpLongStartMinute = 885;
				settings.get("MGC").microScalpLongEndMinute = 889;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mgc_cmom925_skipthu910_mscalp_long885", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalStack(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_m2k_cmom_long900_skipmon", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalStack(settings);
				settings.get("M2K").allowCloseMomentumLongs = true;
				settings.get("M2K").closeMomentumLongStartMinute = 900;
				settings.get("M2K").closeMomentumLongEndMinute = 909;
				settings.get("M2K").closeMomentumLongDowWindowSkipMask = 2;
				settings.get("M2K").closeMomentumLongDowWindowSkipStartMinute = 900;
				settings.get("M2K").closeMomentumLongDowWindowSkipEndMinute = 909;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_m2k_cmom_long900_908_skipmon", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalStack(settings);
				settings.get("M2K").allowCloseMomentumLongs = true;
				settings.get("M2K").closeMomentumLongStartMinute = 900;
				settings.get("M2K").closeMomentumLongEndMinute = 908;
				settings.get("M2K").closeMomentumLongDowWindowSkipMask = 2;
				settings.get("M2K").closeMomentumLongDowWindowSkipStartMinute = 900;
				settings.get("M2K").closeMomentumLongDowWindowSkipEndMinute = 908;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_m2k908_mes_cmom_long920", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalStack(settings);
				settings.get("M2K").allowCloseMomentumLongs = true;
				settings.get("M2K").closeMomentumLongStartMinute = 900;
				settings.get("M2K").closeMomentumLongEndMinute = 908;
				settings.get("M2K").closeMomentumLongDowWindowSkipMask = 2;
				settings.get("M2K").closeMomentumLongDowWindowSkipStartMinute = 900;
				settings.get("M2K").closeMomentumLongDowWindowSkipEndMinute = 908;
				settings.get("MES").allowCloseMomentumLongs = true;
				settings.get("MES").closeMomentumLongStartMinute = 920;
				settings.get("MES").closeMomentumLongEndMinute = 925;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_m2k909_mes_cmom_long920", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalStack(settings);
				settings.get("M2K").allowCloseMomentumLongs = true;
				settings.get("M2K").closeMomentumLongStartMinute = 900;
				settings.get("M2K").closeMomentumLongEndMinute = 909;
				settings.get("M2K").closeMomentumLongDowWindowSkipMask = 2;
				settings.get("M2K").closeMomentumLongDowWindowSkipStartMinute = 900;
				settings.get("M2K").closeMomentumLongDowWindowSkipEndMinute = 909;
				settings.get("MES").allowCloseMomentumLongs = true;
				settings.get("MES").closeMomentumLongStartMinute = 920;
				settings.get("MES").closeMomentumLongEndMinute = 925;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_m2k909_altfri870_mes920", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalStack(settings);
				settings.get("M2K").allowCloseMomentumLongs = true;
				settings.get("M2K").closeMomentumLongStartMinute = 900;
				settings.get("M2K").closeMomentumLongEndMinute = 909;
				settings.get("M2K").closeMomentumLongDowWindowSkipMask = 2;
				settings.get("M2K").closeMomentumLongDowWindowSkipStartMinute = 900;
				settings.get("M2K").closeMomentumLongDowWindowSkipEndMinute = 909;
				settings.get("M2K").closeMomentumLongAltEnabled = true;
				settings.get("M2K").closeMomentumLongAltStartMinute = 870;
				settings.get("M2K").closeMomentumLongAltEndMinute = 889;
				settings.get("M2K").closeMomentumLongAltAllowDowMask = 32;
				settings.get("MES").allowCloseMomentumLongs = true;
				settings.get("MES").closeMomentumLongStartMinute = 920;
				settings.get("MES").closeMomentumLongEndMinute = 925;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_vpb_m2k_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestPlusM2kMesCloseLongStack(settings);
				configureValueArea(settings.get("M2K"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 600, 920, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_vpb_m2k_long", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestPlusM2kMesCloseLongStack(settings);
				configureValueArea(settings.get("M2K"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 600, 920, true, false);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_vpb_mes_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestPlusM2kMesCloseLongStack(settings);
				configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 600, 920, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_vpb_mes_short_720_740", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestPlusM2kMesCloseLongStack(settings);
				configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 720, 740, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_vpb_mes_short_780_810", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestPlusM2kMesCloseLongStack(settings);
				configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 780, 810, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_vpb_mes780_m2k720", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mes_tuewed625", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
				settings.get("MES").openingMomentumShortSkipDowMask = 50;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_m2k_thufri645", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
				settings.get("M2K").openingMomentumShortAltSkipDowMask = 14;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mes_tuewed_m2k_thufri", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
				settings.get("MES").openingMomentumShortSkipDowMask = 50;
				settings.get("M2K").openingMomentumShortAltSkipDowMask = 14;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mes_alt630", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
				settings.get("MES").openingMomentumShortSkipDowMask = 50;
				configureOpeningMomentumShortAlt(settings.get("MES"), 630, 634, 52);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mes_m2k_alt630", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
				settings.get("MES").openingMomentumShortSkipDowMask = 50;
				configureOpeningMomentumShortAlt(settings.get("MES"), 630, 634, 52);
				settings.get("M2K").openingMomentumShortAltSkipDowMask = 14;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mgc_625_635", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 635, 639, 56);
				configureOpeningMomentumShortAlt(settings.get("MGC"), 625, 629, 46);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mes_m2k_mgc", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
				settings.get("MES").openingMomentumShortSkipDowMask = 50;
				configureOpeningMomentumShortAlt(settings.get("MES"), 630, 634, 52);
				settings.get("M2K").openingMomentumShortAltSkipDowMask = 14;
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 635, 639, 56);
				configureOpeningMomentumShortAlt(settings.get("MGC"), 625, 629, 46);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mes_m2k_mgc_tight", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 636, 636, 58);
				configureOpeningMomentumShortAlt(settings.get("MGC"), 638, 640, 60);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mgc_tight_only", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 636, 636, 58);
				configureOpeningMomentumShortAlt(settings.get("MGC"), 638, 640, 60);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mnq_mon651", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedStack(settings);
				configureOpeningMomentumShortAlt(settings.get("MNQ"), 651, 654, 60);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mnq_mon651_rr50", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedStack(settings);
				configureOpeningMomentumShortAlt(settings.get("MNQ"), 651, 654, 60);
				settings.get("MNQ").openingMomentumRewardRisk = 0.50;
			}
		}));
		for (final int nqLorbEnd : new int[] {672, 673, 674, 675}) {
			values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mnq651_lorb" + nqLorbEnd, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestValueAreaExpandedMnqStack(settings);
					settings.get("NQ").lateOrbContinuationEndMinute = nqLorbEnd;
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mnq651_lorb" + nqLorbEnd + "_v120", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestValueAreaExpandedMnqStack(settings);
					settings.get("NQ").lateOrbContinuationEndMinute = nqLorbEnd;
					settings.get("NQ").lateOrbContinuationMinVolumeRatio = 1.20;
				}
			}));
		}
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mnq651_echo_mes", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqStack(settings);
				configureMicroEcho(settings.get("MES"), 8, 8, 0.70, 0.55, 8.0, 0.50, 600, 900, false, true, 0.0, 3, 2);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mnq651_echo_m2k", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqStack(settings);
				configureMicroEcho(settings.get("M2K"), 8, 8, 0.70, 0.55, 8.0, 0.50, 600, 900, false, true, 0.0, 3, 2);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpb_expand_mnq651_echo_mnq", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqStack(settings);
				configureMicroEcho(settings.get("MNQ"), 8, 8, 0.70, 0.55, 8.0, 0.50, 600, 900, false, true, 0.0, 3, 2);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqStack(settings);
				configureOpeningMomentumShortAlt(settings.get("MES"), 621, 621, 26);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mgc647", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 647, 647, 46);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mgc647_mon638", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 647, 647, 46);
				configureOpeningMomentumShortAlt(settings.get("MGC"), 638, 640, 60);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_mgc", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqStack(settings);
				configureOpeningMomentumShortAlt(settings.get("MES"), 621, 621, 26);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 647, 647, 46);
				configureOpeningMomentumShortAlt(settings.get("MGC"), 638, 640, 60);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_extra_mes_clean", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 626, 626, 54);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 646, 646, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 649, 649, 46);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_extra_mes_tue648", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 648, 649, 58);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_extra_m2k", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 623, 623, 30);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 643, 643, 54);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_extra_mes_m2k", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 626, 626, 54);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 646, 646, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 649, 649, 46);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 623, 623, 30);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 643, 643, 54);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_extra_full", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 626, 626, 54);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 646, 646, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 649, 649, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 648, 649, 58);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 623, 623, 30);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 643, 643, 54);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_shift_mes620", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 620, 620, 26);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_shift_mes_clean", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 620, 620, 26);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 623, 623, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 625, 625, 54);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 645, 645, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 648, 648, 46);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_shift_m2k", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 622, 622, 30);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 642, 642, 54);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_shift_mgc", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 660, 660, 62);
				addOpeningMomentumShortExtraWindow(settings.get("MGC"), 646, 646, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MGC"), 637, 639, 60);
				addOpeningMomentumShortExtraWindow(settings.get("MGC"), 624, 624, 58);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_mnq651_mes621_shift_all", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 620, 620, 26);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 623, 623, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 625, 625, 54);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 645, 645, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 648, 648, 46);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 622, 622, 30);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 642, 642, 54);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 660, 660, 62);
				addOpeningMomentumShortExtraWindow(settings.get("MGC"), 646, 646, 46);
				addOpeningMomentumShortExtraWindow(settings.get("MGC"), 637, 639, 60);
				addOpeningMomentumShortExtraWindow(settings.get("MGC"), 624, 624, 58);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_prune_mes_mscalp", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				settings.get("MES").microScalp.enabled = false;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_prune_mes_mscalp_mes_wide", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				settings.get("MES").microScalp.enabled = false;
				settings.get("MES").openingMomentumShortSkipDowMask = 50;
				settings.get("MES").openingMomentumShortStartMinute = 620;
				settings.get("MES").openingMomentumShortEndMinute = 649;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_prune_mes_mscalp_mgc_wide", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				settings.get("MES").microScalp.enabled = false;
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 20);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_skip_mscalp_720", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				settings.get("MES").microScalpSkipStartMinute = 720;
				settings.get("MES").microScalpSkipEndMinute = 749;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_skip_mscalp_840", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				settings.get("MES").microScalpSkipStartMinute = 840;
				settings.get("MES").microScalpSkipEndMinute = 859;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_shadow_mnq_short660", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureMicroShadow(settings.get("MNQ"), 12, 10, 0.35, 1.00, 12.0, 0.0, 660, 661, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_shadow_mnq_long760", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureMicroShadow(settings.get("MNQ"), 12, 10, 0.35, 1.00, 12.0, 0.0, 760, 761, true, false);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_shadow_mnq_dual", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureMicroShadow(settings.get("MNQ"), 12, 10, 0.35, 1.00, 12.0, 0.0, 660, 761, true, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_rcb_mnq_long917", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureRangeCompression(settings.get("MNQ"), 4, 8, 4, 0.50, 0.55, 10.0, 0.25, 917, 917, true, false);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_rcb_mnq_short764", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureRangeCompression(settings.get("MNQ"), 4, 8, 4, 0.50, 0.55, 10.0, 0.25, 764, 764, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_rcb_mnq_long851", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureRangeCompression(settings.get("MNQ"), 4, 8, 4, 0.50, 0.55, 10.0, 0.25, 851, 851, true, false);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_orb_mnq_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureCompressedOrb(settings.get("MNQ"), 32.0);
				settings.get("MNQ").allowOrbLongs = false;
				settings.get("MNQ").allowOrbShorts = true;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_shadow_mnq_nqorb", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestVpbSkipStack(settings);
				configureCompressedOrb(settings.get("NQ"), 32.0);
				settings.get("NQ").allowOrbLongs = false;
				settings.get("NQ").allowOrbShorts = true;
				configureMicroShadow(settings.get("MNQ"), 12, 10, 0.35, 1.00, 12.0, 0.0, 660, 661, false, true);
			}
		}));
		for (final int maxOpen : new int[] {4, 5}) {
			values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_open" + maxOpen, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestShiftAllStack(settings);
				}
			}, maxOpen));
		}
		for (final int maxContracts : new int[] {60, 75, 100}) {
			values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_agg" + maxContracts, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestShiftAllStack(settings);
				}
			}, 3, maxContracts));
		}
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_vpb_mes720_810", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 720, 810, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_vpb_mes720_810_skip859", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 720, 810, false, true);
				settings.get("MES").microScalpSkipStartMinute = 859;
				settings.get("MES").microScalpSkipEndMinute = 859;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_vpb_mes720_810_skip915", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 720, 810, false, true);
				settings.get("MES").microScalpSkipStartMinute = 915;
				settings.get("MES").microScalpSkipEndMinute = 915;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_vpb_mes720_810_skip858", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 720, 810, false, true);
				settings.get("MES").microScalpSkipStartMinute = 858;
				settings.get("MES").microScalpSkipEndMinute = 858;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_vpb_mes720_810_skip914", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestShiftAllStack(settings);
				configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 720, 810, false, true);
				settings.get("MES").microScalpSkipStartMinute = 914;
				settings.get("MES").microScalpSkipEndMinute = 914;
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_surgical_pockets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestVpbSkipStack(settings);
				addEinsteinSurgicalPockets(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_surgical_pruned", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestVpbSkipStack(settings);
				addEinsteinPrunedSurgicalPockets(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_surgical_mes_omom_broad", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalPrunedStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_surgical_mgc_omom_broad", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalPrunedStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_surgical_m2k_omom_broad", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalPrunedStack(settings);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_surgical_micro_omom_broad", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalPrunedStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 0);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 0);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_surgical_broad_positive_cells", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalPrunedStack(settings);
				addBroadPositiveOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_surgical_broad_positive_pruned", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalPrunedStack(settings);
				addBroadPositivePrunedOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_broad_pruned_singlewin_burst", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestBroadPrunedStack(settings);
				addBroadSingleWinnerOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_broad_pruned_singlewin_pruned", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestBroadPrunedStack(settings);
				addBroadSingleWinnerPrunedOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_singlewin_micro_omom_broad", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSingleWinPrunedStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 0);
				configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 0);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_remaining_micro_omom_long_broad", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestRemainingPrunedStack(settings);
				configureOpeningMomentumLongOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, 0);
				configureOpeningMomentumLongOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 0);
				configureOpeningMomentumLongOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_remaining_long_positive_cells", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestRemainingPrunedStack(settings);
				addLongPositiveOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_long_positive_second_wave", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestLongPositiveStack(settings);
				addLongSecondWaveOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_second_wave_third_wave_omom_100pct", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestLongSecondWaveStack(settings);
				addThirdWaveOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_second_wave_third_wave_pruned", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestLongSecondWaveStack(settings);
				addThirdWavePrunedOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_second_wave_third_probe_long_all", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestLongSecondWaveStack(settings);
				addOpeningMomentumLongExtraWindow(settings.get("MES"), 620, 649, 0);
				addOpeningMomentumLongExtraWindow(settings.get("MGC"), 620, 649, 0);
				addOpeningMomentumLongExtraWindow(settings.get("M2K"), 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_second_wave_third_probe_short_all", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestLongSecondWaveStack(settings);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 620, 649, 0);
				addOpeningMomentumShortExtraWindow(settings.get("MGC"), 620, 649, 0);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_second_wave_third_probe_both_all", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestLongSecondWaveStack(settings);
				addOpeningMomentumLongExtraWindow(settings.get("MES"), 620, 649, 0);
				addOpeningMomentumLongExtraWindow(settings.get("MGC"), 620, 649, 0);
				addOpeningMomentumLongExtraWindow(settings.get("M2K"), 620, 649, 0);
				addOpeningMomentumShortExtraWindow(settings.get("MES"), 620, 649, 0);
				addOpeningMomentumShortExtraWindow(settings.get("MGC"), 620, 649, 0);
				addOpeningMomentumShortExtraWindow(settings.get("M2K"), 620, 649, 0);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_singlewin_remaining_positive", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSingleWinPrunedStack(settings);
				addRemainingPositiveOmomCells(settings);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_singlewin_remaining_pruned", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSingleWinPrunedStack(settings);
				addRemainingPrunedOmomCells(settings);
			}
		}));
		for (final int skipMask : new int[] {20, 42, 50, 54, 58, 30}) {
			values.add(new SimpleScenario("compose_lorb_e671_best_surgical_micro_omom_mask" + skipMask, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestSurgicalPrunedStack(settings);
					configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 620, 649, skipMask);
					configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, skipMask);
					configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 620, 649, skipMask);
				}
			}));
		}
		values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_wft_omom_short_core", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestVpbSkipStack(settings);
				configureWinnerFollowThroughAll(settings, 8, "OMOM", false, true, 620, 670, 1, 0.0, 0.60, 0.55, 10.0, 0.0, 0.0, 0.54, 0.46, 48.0, 52.0, 8);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_wft_omom_short_loose", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestVpbSkipStack(settings);
				configureWinnerFollowThroughAll(settings, 10, "OMOM", false, true, 620, 675, 1, 0.0, 0.45, 0.50, 14.0, 0.0, 0.0, 0.52, 0.48, 47.0, 55.0, 8);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_wft_core_sources", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestVpbSkipStack(settings);
				configureWinnerFollowThroughAll(settings, 8, "OMOM,CMOM,VPB", true, true, 620, 910, 1, 0.0, 0.60, 0.55, 10.0, 0.0, 0.0, 0.54, 0.46, 48.0, 52.0, 8);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_wft_core_plus_surgical", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestVpbSkipStack(settings);
				addEinsteinSurgicalPockets(settings);
				configureWinnerFollowThroughAll(settings, 8, "OMOM,CMOM,VPB", true, true, 620, 910, 1, 0.0, 0.60, 0.55, 10.0, 0.0, 0.0, 0.54, 0.46, 48.0, 52.0, 8);
			}
		}));
		for (final int delayBars : new int[] {1, 2}) {
			for (final double rewardRisk : new double[] {0.45, 0.55, 0.65}) {
				for (final double maxRiskTicks : new double[] {8.0, 12.0}) {
					values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_wft_grid_d" + delayBars + "_rr" + tag(rewardRisk) + "_r" + Math.round(maxRiskTicks), new ScenarioApplier() {
						public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
							applyCurrentBestVpbSkipStack(settings);
							configureWinnerFollowThroughAll(settings, 8, "OMOM,CMOM,VPB", true, true, 620, 910, delayBars, 0.0, 0.55, rewardRisk, maxRiskTicks, 0.0, 0.0, 0.54, 0.46, 48.0, 52.0, 8);
						}
					}));
				}
			}
		}
		for (final double rsiMax : new double[] {45.0, 42.0, 38.0}) {
			values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_mes_wide_rsi" + Math.round(rsiMax), new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestShiftAllStack(settings);
					settings.get("MES").openingMomentumShortStartMinute = 620;
					settings.get("MES").openingMomentumShortEndMinute = 649;
					settings.get("MES").openingMomentumShortSkipDowMask = 50;
					settings.get("MES").openingMomentumShortMaxRsi = rsiMax;
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e671_best_shiftall_mgc_wide_rsi" + Math.round(rsiMax), new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestShiftAllStack(settings);
					configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 20);
					settings.get("MGC").openingMomentumShortMaxRsi = rsiMax;
				}
			}));
		}
		for (final double rsiMin : new double[] {24.0, 28.0, 32.0, 36.0}) {
			values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_mes_wide_rsi" + Math.round(rsiMin) + "_50", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestVpbSkipStack(settings);
					settings.get("MES").openingMomentumShortStartMinute = 620;
					settings.get("MES").openingMomentumShortEndMinute = 649;
					settings.get("MES").openingMomentumShortSkipDowMask = 50;
					settings.get("MES").openingMomentumShortMinRsi = rsiMin;
					settings.get("MES").openingMomentumShortMaxRsi = 50.0;
				}
			}));
			values.add(new SimpleScenario("compose_lorb_e671_best_vpbskip_mgc_wide_rsi" + Math.round(rsiMin) + "_50", new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyCurrentBestVpbSkipStack(settings);
					configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 620, 649, 20);
					settings.get("MGC").openingMomentumShortMinRsi = rsiMin;
					settings.get("MGC").openingMomentumShortMaxRsi = 50.0;
				}
			}));
		}
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_vpb_mgc_long", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestPlusM2kMesCloseLongStack(settings);
				configureValueArea(settings.get("MGC"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 24.0, 0.0, 600, 920, true, false);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_mnq_cmom_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalStack(settings);
				settings.get("M2K").allowCloseMomentumLongs = true;
				settings.get("M2K").closeMomentumLongStartMinute = 900;
				settings.get("M2K").closeMomentumLongEndMinute = 909;
				settings.get("M2K").closeMomentumLongDowWindowSkipMask = 2;
				settings.get("M2K").closeMomentumLongDowWindowSkipStartMinute = 900;
				settings.get("M2K").closeMomentumLongDowWindowSkipEndMinute = 909;
				settings.get("MES").allowCloseMomentumLongs = true;
				settings.get("MES").closeMomentumLongStartMinute = 920;
				settings.get("MES").closeMomentumLongEndMinute = 925;
				configureCloseMomentum(settings.get("MNQ"), 3, false, true, 870, 16.0, 0.6, 0.85);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_best_mnq_cmom_long", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyCurrentBestSurgicalStack(settings);
				settings.get("M2K").allowCloseMomentumLongs = true;
				settings.get("M2K").closeMomentumLongStartMinute = 900;
				settings.get("M2K").closeMomentumLongEndMinute = 909;
				settings.get("M2K").closeMomentumLongDowWindowSkipMask = 2;
				settings.get("M2K").closeMomentumLongDowWindowSkipStartMinute = 900;
				settings.get("M2K").closeMomentumLongDowWindowSkipEndMinute = 909;
				settings.get("MES").allowCloseMomentumLongs = true;
				settings.get("MES").closeMomentumLongStartMinute = 920;
				settings.get("MES").closeMomentumLongEndMinute = 925;
				configureCloseMomentum(settings.get("MNQ"), 3, true, false, 900, 16.0, 0.6, 0.85);
				settings.get("MNQ").closeMomentumLongEndMinute = 925;
			}
		}));
		for (final int maxOpen : new int[] {4, 5}) {
			values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_risk20_open" + maxOpen, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyPrunedDenseMicroStack(settings);
					configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
					configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
					configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
					settings.get("MES").openingMomentumMaxRiskTicks = 20.0;
				}
			}, maxOpen));
			values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_m2k_tuethu_mes_risk32_open" + maxOpen, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					applyPrunedDenseMicroStack(settings);
					configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
					configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
					configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
					settings.get("MES").openingMomentumMaxRiskTicks = 32.0;
				}
			}, maxOpen));
		}
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mnq_mon_omom_rr50", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MNQ"), 10, 15, 0.55, 0.50, 650, 654, 60);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mes_m2kthu_mnqmon", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 645, 649, 46);
				configureOpeningMomentumShortOnly(settings.get("MNQ"), 10, 15, 0.55, 0.50, 650, 654, 60);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_mes_m2ktue_mnqmon", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
				configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
				configureOpeningMomentumShortOnly(settings.get("MNQ"), 10, 15, 0.55, 0.50, 650, 654, 60);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_echo_mes", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureMicroEcho(settings.get("MES"), 12, 6, 0.55, 0.65, 10.0, 0.0, 570, 930, false, true, 0.0, 3, 3);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_echo_mes_profit150", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureMicroEcho(settings.get("MES"), 12, 6, 0.55, 0.65, 10.0, 0.0, 570, 930, false, true, 150.0, 3, 3);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_echo_mes_mnq", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureMicroEcho(settings.get("MES"), 12, 6, 0.55, 0.65, 10.0, 0.0, 570, 930, false, true, 0.0, 3, 3);
				configureMicroEcho(settings.get("MNQ"), 12, 6, 0.55, 0.65, 10.0, 0.0, 570, 930, false, true, 0.0, 3, 3);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_echo_mes_mnq_profit150", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyPrunedDenseMicroStack(settings);
				configureMicroEcho(settings.get("MES"), 12, 6, 0.55, 0.65, 10.0, 0.0, 570, 930, false, true, 150.0, 3, 3);
				configureMicroEcho(settings.get("MNQ"), 12, 6, 0.55, 0.65, 10.0, 0.0, 570, 930, false, true, 150.0, 3, 3);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_echo_self_m2k_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRisk20Stack(settings);
				configureMicroEcho(settings.get("M2K"), 8, 6, 0.45, 0.55, 8.0, 0.0, 570, 930, false, true, 0.0, 3, 3);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_echo_self_m2k_short_strict", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRisk20Stack(settings);
				configureMicroEcho(settings.get("M2K"), 6, 10, 0.65, 0.55, 8.0, 0.5, 590, 920, false, true, 0.0, 3, 2);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_echo_self_mgc_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRisk20Stack(settings);
				configureMicroEcho(settings.get("MGC"), 8, 6, 0.45, 0.55, 10.0, 0.0, 570, 930, false, true, 0.0, 3, 3);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_pruned_surgical_echo_self_mes_short", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applySurgicalM2kTueThuMesRisk20Stack(settings);
				configureMicroEcho(settings.get("MES"), 8, 6, 0.45, 0.55, 8.0, 0.0, 570, 930, false, true, 0.0, 3, 3);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_e671_gold_mscalp_mes_mgc_wedfri", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				applyGoldCloseStack(settings, 671);
				configureMicroScalp(settings.get("MES"), 10, 20, 0.65, 0.55, 8.0, 0.75, false, true, 590, 920, 0, 0, 22);
				configureMicroScalp(settings.get("MGC"), 8, 20, 0.65, 0.55, 10.0, 0.75, false, true, 590, 920, 0, 0, 22);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_nq32_mnq_short_echo", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				configureLateOrbContinuation(settings.get("NQ"), 32.0);
				configureMicroShadow(settings.get("MNQ"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_lorb_nq32_dual_short_echo", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				configureLateOrbContinuation(settings.get("NQ"), 32.0);
				configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
				configureMicroShadow(settings.get("MNQ"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
			}
		}));
		values.add(new SimpleScenario("compose_late_nq32_mes_shadow", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				configureCompressedOrb(nq, 32.0);
				nq.allowOrbLongs = false;
				nq.allowOrbShorts = true;
				nq.orbShortSkipStartMinute = 570;
				nq.orbShortSkipEndMinute = 659;
				configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, true, true);
			}
		}));
		values.add(new SimpleScenario("compose_late_nq40_mes_shadow", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				configureCompressedOrb(nq, 40.0);
				nq.allowOrbLongs = false;
				nq.allowOrbShorts = true;
				nq.orbShortSkipStartMinute = 570;
				nq.orbShortSkipEndMinute = 659;
				configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, true, true);
			}
		}));
		values.add(new SimpleScenario("compose_late_nq32_mes_shadow_cap4", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				configureCompressedOrb(nq, 32.0);
				nq.allowOrbLongs = false;
				nq.allowOrbShorts = true;
				nq.orbShortSkipStartMinute = 570;
				nq.orbShortSkipEndMinute = 659;
				configureMicroShadow(settings.get("MES"), 4, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, true, true);
			}
		}));
		values.add(new SimpleScenario("compose_late_nq32_mes_shadow_b5", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				configureCompressedOrb(nq, 32.0);
				nq.allowOrbLongs = false;
				nq.allowOrbShorts = true;
				nq.orbShortSkipStartMinute = 570;
				nq.orbShortSkipEndMinute = 659;
				configureMicroShadow(settings.get("MES"), 4, 5, 0.35, 1.00, 12.0, 0.0, 570, 930, true, true);
			}
		}));
		values.add(new SimpleScenario("compose_late_nq32_mes_shadow_cmom", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				configureCompressedOrb(nq, 32.0);
				nq.allowOrbLongs = false;
				nq.allowOrbShorts = true;
				nq.orbShortSkipStartMinute = 570;
				nq.orbShortSkipEndMinute = 659;
				configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, true, true);
				enableCloseMomentumRefills(settings);
			}
		}));
		values.add(new SimpleScenario("compose_late_nq32_mes_shadow_cmom_late_m2k", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
				FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
				configureCompressedOrb(nq, 32.0);
				nq.allowOrbLongs = false;
				nq.allowOrbShorts = true;
				nq.orbShortSkipStartMinute = 570;
				nq.orbShortSkipEndMinute = 659;
				configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, true, true);
				enableCloseMomentumRefills(settings);
				settings.get("M2K").closeMomentumShortStartMinute = 889;
			}
		}));
		for (final int m2kStart : new int[] {885, 886, 887, 888}) {
			values.add(new SimpleScenario("compose_late_nq32_mes_shadow_cmom_m2k" + m2kStart, new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risksMap) {
					FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
					configureCompressedOrb(nq, 32.0);
					nq.allowOrbLongs = false;
					nq.allowOrbShorts = true;
					nq.orbShortSkipStartMinute = 570;
					nq.orbShortSkipEndMinute = 659;
					configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, true, true);
					enableCloseMomentumRefills(settings);
					settings.get("M2K").closeMomentumShortStartMinute = m2kStart;
				}
			}));
		}
	}

	private static void enableCloseMomentumRefills(Map<String, FuturesManager.FuturesStrategySettings> settings) {
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
	}

	private static void applyBaseline(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
		resetExperimentalModules(settings);
		enablePdbSweepRefills(settings);
		configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, 1.45, 0.65, 16.0, 8.0, 15);
		configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
		configureOpeningMomentumExpansion(settings.get("MNQ"), 10, 0.50, 0.55);
		settings.get("MNQ").requireHigherTimeframeGuard = true;
	}

	private static void resetExperimentalModules(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings symbolSettings = settings.get(symbol);
			if (symbolSettings == null) {
				continue;
			}
			symbolSettings.lateOrbContinuation.enabled = false;
			symbolSettings.microScalp.enabled = false;
			symbolSettings.microShadow.enabled = false;
			symbolSettings.microEcho.enabled = false;
			symbolSettings.trendLadder.enabled = false;
			symbolSettings.rangeCompressionBreakout.enabled = false;
			symbolSettings.valueAreaReclaim.enabled = false;
			symbolSettings.allowRangeCompressionLongs = true;
			symbolSettings.allowRangeCompressionShorts = true;
			symbolSettings.allowMicroScalpLongs = true;
			symbolSettings.allowMicroScalpShorts = true;
			symbolSettings.microScalpLongStartMinute = 0;
			symbolSettings.microScalpLongEndMinute = 0;
			symbolSettings.microScalpShortStartMinute = 0;
			symbolSettings.microScalpShortEndMinute = 0;
			symbolSettings.allowMicroEchoLongs = true;
			symbolSettings.allowMicroEchoShorts = true;
			symbolSettings.allowValueAreaLongs = true;
			symbolSettings.allowValueAreaShorts = true;
			symbolSettings.openingMomentumShortAltEnabled = false;
			symbolSettings.openingMomentumShortAltStartMinute = 0;
			symbolSettings.openingMomentumShortAltEndMinute = 0;
			symbolSettings.openingMomentumShortAltSkipDowMask = 0;
			symbolSettings.openingMomentumShortExtraWindows = "";
			symbolSettings.openingMomentumShortMinRsi = 0.0;
			symbolSettings.openingMomentumShortMaxRsi = 100.0;
			symbolSettings.closeMomentumLongDowWindowSkipMask = 0;
			symbolSettings.closeMomentumLongDowWindowSkipStartMinute = 0;
			symbolSettings.closeMomentumLongDowWindowSkipEndMinute = 0;
			symbolSettings.closeMomentumLongAltEnabled = false;
			symbolSettings.closeMomentumLongAltStartMinute = 0;
			symbolSettings.closeMomentumLongAltEndMinute = 0;
			symbolSettings.closeMomentumLongAltAllowDowMask = 0;
		}
	}

	private static void configureMicroShadow(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, int startMinute, int endMinute, boolean allowLongs, boolean allowShorts) {
		if (settings == null) {
			return;
		}
		settings.microShadow.enabled = true;
		settings.microShadow.maxTradesPerDay = cap;
		settings.microShadowBucketMinutes = bucketMinutes;
		settings.microShadowMinVolumeRatio = volumeRatio;
		settings.microShadowRewardRisk = rewardRisk;
		settings.microShadowMaxRiskTicks = maxRiskTicks;
		settings.microShadowMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.microShadowStartMinute = startMinute;
		settings.microShadowEndMinute = endMinute;
		settings.microShadowMaxHoldBars = 10;
		settings.allowMicroShadowLongs = allowLongs;
		settings.allowMicroShadowShorts = allowShorts;
	}

	private static void configureTrendLadder(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double maxRiskTicks, double rewardRisk, double pullbackTicks, double minTrendSlopeTicks, int startMinute, int endMinute, int maxHoldBars, boolean requireHigherTimeframeGuard) {
		if (settings == null) {
			return;
		}
		settings.trendLadder.enabled = true;
		settings.trendLadder.maxTradesPerDay = cap;
		settings.allowTrendLadderLongs = true;
		settings.allowTrendLadderShorts = true;
		settings.trendLadderBucketMinutes = bucketMinutes;
		settings.trendLadderMinVolumeRatio = volumeRatio;
		settings.trendLadderMaxRiskTicks = maxRiskTicks;
		settings.trendLadderRewardRisk = rewardRisk;
		settings.trendLadderPullbackTicks = pullbackTicks;
		settings.trendLadderMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.trendLadderStartMinute = startMinute;
		settings.trendLadderEndMinute = endMinute;
		settings.trendLadderMaxHoldBars = maxHoldBars;
		settings.requireHigherTimeframeGuard = requireHigherTimeframeGuard;
	}

	private static void configureRangeCompression(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, int boxBars, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, int startMinute, int endMinute, boolean allowLongs, boolean allowShorts) {
		if (settings == null) {
			return;
		}
		settings.rangeCompressionBreakout.enabled = true;
		settings.rangeCompressionBreakout.maxTradesPerDay = cap;
		settings.allowRangeCompressionLongs = allowLongs;
		settings.allowRangeCompressionShorts = allowShorts;
		settings.rangeCompressionStartMinute = startMinute;
		settings.rangeCompressionEndMinute = endMinute;
		settings.rangeCompressionBars = boxBars;
		settings.rangeCompressionBucketMinutes = bucketMinutes;
		settings.rangeCompressionMaxAtrRatio = 0.70;
		settings.rangeCompressionMinVolumeRatio = volumeRatio;
		settings.rangeCompressionMaxRiskTicks = maxRiskTicks;
		settings.rangeCompressionRewardRisk = rewardRisk;
		settings.rangeCompressionMaxDistanceTicks = 52.0;
		settings.rangeCompressionMinBodyPct = 14.0;
		settings.rangeCompressionMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.rangeCompressionMaxHoldBars = 10;
	}

	private static void configureValueArea(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double profilePct, double binTicks, double reclaimTicks, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, int startMinute, int endMinute, boolean allowLongs, boolean allowShorts) {
		if (settings == null) {
			return;
		}
		settings.valueAreaReclaim.enabled = true;
		settings.valueAreaReclaim.maxTradesPerDay = cap;
		settings.allowValueAreaLongs = allowLongs;
		settings.allowValueAreaShorts = allowShorts;
		settings.valueAreaStartMinute = startMinute;
		settings.valueAreaEndMinute = endMinute;
		settings.valueAreaBucketMinutes = bucketMinutes;
		settings.valueAreaPct = profilePct;
		settings.valueAreaBinTicks = binTicks;
		settings.valueAreaReclaimTicks = reclaimTicks;
		settings.valueAreaMinVolumeRatio = volumeRatio;
		settings.valueAreaMaxRiskTicks = maxRiskTicks;
		settings.valueAreaRewardRisk = rewardRisk;
		settings.valueAreaMaxHoldBars = 30;
		settings.rangeCompressionMinTrendSlopeTicks = minTrendSlopeTicks;
	}

	private static void configureWinnerFollowThroughAll(Map<String, FuturesManager.FuturesStrategySettings> settings, int cap, String sourceCodes, boolean allowLongs, boolean allowShorts, int startMinute, int endMinute, int delayBars, double minSourcePnl, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, double minBodyPct, double longCloseLocation, double shortCloseLocation, double longMinRsi, double shortMaxRsi, int maxHoldBars) {
		for (String symbol : SYMBOL_LIST) {
			configureWinnerFollowThrough(settings.get(symbol), cap, sourceCodes, allowLongs, allowShorts, startMinute, endMinute, delayBars, minSourcePnl, volumeRatio, rewardRisk, maxRiskTicks, minTrendSlopeTicks, minBodyPct, longCloseLocation, shortCloseLocation, longMinRsi, shortMaxRsi, maxHoldBars);
		}
	}

	private static void configureWinnerFollowThrough(FuturesManager.FuturesStrategySettings settings, int cap, String sourceCodes, boolean allowLongs, boolean allowShorts, int startMinute, int endMinute, int delayBars, double minSourcePnl, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, double minBodyPct, double longCloseLocation, double shortCloseLocation, double longMinRsi, double shortMaxRsi, int maxHoldBars) {
		if (settings == null) {
			return;
		}
		settings.winnerFollowThrough.enabled = true;
		settings.winnerFollowThrough.maxTradesPerDay = cap;
		settings.allowWinnerFollowThroughLongs = allowLongs;
		settings.allowWinnerFollowThroughShorts = allowShorts;
		settings.winnerFollowThroughSourceCodes = sourceCodes;
		settings.winnerFollowThroughStartMinute = startMinute;
		settings.winnerFollowThroughEndMinute = endMinute;
		settings.winnerFollowThroughDelayBars = delayBars;
		settings.winnerFollowThroughMinSourcePnl = minSourcePnl;
		settings.winnerFollowThroughMinVolumeRatio = volumeRatio;
		settings.winnerFollowThroughRewardRisk = rewardRisk;
		settings.winnerFollowThroughMaxRiskTicks = maxRiskTicks;
		settings.winnerFollowThroughMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.winnerFollowThroughMinBodyPct = minBodyPct;
		settings.winnerFollowThroughLongCloseLocation = longCloseLocation;
		settings.winnerFollowThroughShortCloseLocation = shortCloseLocation;
		settings.winnerFollowThroughLongMinRsi = longMinRsi;
		settings.winnerFollowThroughShortMaxRsi = shortMaxRsi;
		settings.winnerFollowThroughMaxHoldBars = maxHoldBars;
	}

	private static void configureLooseRangeCompression(FuturesManager.FuturesStrategySettings settings, int cap, double rewardRisk) {
		configureRangeCompression(settings, cap, 6, 3, 0.0, rewardRisk, 36.0, 0.0, 575, 920, true, true);
		if (settings == null) {
			return;
		}
		settings.rangeCompressionMaxAtrRatio = 1.15;
		settings.rangeCompressionMaxDistanceTicks = 120.0;
		settings.rangeCompressionMinBodyPct = 0.0;
		settings.rangeCompressionMaxHoldBars = 8;
	}

	private static void configureLooseValueArea(FuturesManager.FuturesStrategySettings settings, int cap, double rewardRisk) {
		configureValueArea(settings, cap, 10, 0.70, 4.0, 1.0, 0.0, rewardRisk, 80.0, 0.0, 570, 930, true, true);
		if (settings == null) {
			return;
		}
		settings.valueAreaMaxHoldBars = 18;
	}

	private static void configureCompressedOrb(FuturesManager.FuturesStrategySettings settings, double maxRiskTicks) {
		if (settings == null) {
			return;
		}
		settings.orb.enabled = true;
		settings.orb.maxTradesPerDay = Math.max(settings.orb.maxTradesPerDay, 3);
		settings.enableCompressedOrbBreakout = true;
		settings.orbCompressedMaxRiskTicks = maxRiskTicks;
	}

	private static void configureOrbRetest(FuturesManager.FuturesStrategySettings settings, double maxRiskTicks) {
		if (settings == null) {
			return;
		}
		settings.orb.enabled = true;
		settings.orb.maxTradesPerDay = Math.max(settings.orb.maxTradesPerDay, 4);
		settings.enableOrbRetest = true;
		settings.orbRetestMaxRiskTicks = maxRiskTicks;
		settings.orbRetestStartMinutes = 0;
		settings.orbRetestEndMinutes = 135;
		settings.orbRetestSkipStartMinute = 0;
		settings.orbRetestSkipEndMinute = 0;
	}

	private static void configureLateOrbContinuation(FuturesManager.FuturesStrategySettings settings, double maxRiskTicks) {
		configureLateOrbContinuation(settings, maxRiskTicks, 1.70);
	}

	private static void configureLateOrbContinuation(FuturesManager.FuturesStrategySettings settings, double maxRiskTicks, double volumeRatio) {
		if (settings == null) {
			return;
		}
		settings.lateOrbContinuation.enabled = true;
		settings.lateOrbContinuation.maxTradesPerDay = 2;
		settings.allowLateOrbContinuationLongs = false;
		settings.allowLateOrbContinuationShorts = true;
		settings.lateOrbContinuationStartMinute = 660;
		settings.lateOrbContinuationEndMinute = 660;
		settings.lateOrbContinuationMinVolumeRatio = volumeRatio;
		settings.lateOrbContinuationMaxRiskTicks = maxRiskTicks;
		settings.lateOrbContinuationRewardRisk = 1.2;
		settings.lateOrbContinuationMaxHoldBars = 120;
	}

	private static void applyGoldCloseStack(Map<String, FuturesManager.FuturesStrategySettings> settings, int endMinute) {
		configureLateOrbContinuation(settings.get("NQ"), 32.0, 1.10);
		settings.get("NQ").lateOrbContinuationEndMinute = endMinute;
		configureMicroShadow(settings.get("MES"), 2, 10, 0.35, 1.00, 12.0, 0.0, 570, 930, false, true);
		enableCloseMomentumRefills(settings);
		settings.get("M2K").closeMomentumShortStartMinute = 889;
		FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
		mgc.closeMomentum.enabled = true;
		mgc.closeMomentum.maxTradesPerDay = 3;
		mgc.allowCloseMomentumLongs = false;
		mgc.allowCloseMomentumShorts = true;
		mgc.closeMomentumShortStartMinute = 865;
		mgc.closeMomentumMinMoveTicks = 16.0;
		mgc.closeMomentumVolumeRatio = 0.5;
	}

	private static void applyPrunedDenseMicroStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyGoldCloseStack(settings, 671);
		configureMicroScalp(settings.get("MES"), 16, 10, 0.60, 0.55, 8.0, 0.75, false, true, 590, 920, 0, 0, 20);
		configureMicroScalp(settings.get("MGC"), 12, 10, 0.60, 0.55, 10.0, 0.75, false, true, 590, 920, 0, 0, 22);
	}

	private static void applySurgicalM2kTueThuMesRisk20Stack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyPrunedDenseMicroStack(settings);
		configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
		configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
		configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
		settings.get("MES").openingMomentumMaxRiskTicks = 20.0;
	}

	private static void applySurgicalM2kTueThuMesRsi50Stack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyPrunedDenseMicroStack(settings);
		configureOpeningMomentumShortOnly(settings.get("M2K"), 10, 15, 0.55, 0.55, 625, 629, 58);
		configureOpeningMomentumShortAlt(settings.get("M2K"), 645, 649, 46);
		configureOpeningMomentumShortOnly(settings.get("MES"), 10, 15, 0.55, 0.55, 625, 629, 58);
		settings.get("MES").openingMomentumMaxRiskTicks = 32.0;
		settings.get("MES").openingMomentumShortMaxRsi = 50.0;
	}

	private static void applySurgicalM2kTueThuMesRsi50MgcLongStack(Map<String, FuturesManager.FuturesStrategySettings> settings, int mgcLongEndMinute) {
		applySurgicalM2kTueThuMesRsi50Stack(settings);
		settings.get("MGC").allowCloseMomentumLongs = true;
		settings.get("MGC").closeMomentumLongStartMinute = 900;
		settings.get("MGC").closeMomentumLongEndMinute = mgcLongEndMinute;
	}

	private static void applyCurrentBestSurgicalStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applySurgicalM2kTueThuMesRsi50MgcLongStack(settings, 925);
		settings.get("MGC").closeMomentumLongDowWindowSkipMask = 16;
		settings.get("MGC").closeMomentumLongDowWindowSkipStartMinute = 910;
		settings.get("MGC").closeMomentumLongDowWindowSkipEndMinute = 925;
		settings.get("MGC").allowMicroScalpLongs = true;
		settings.get("MGC").microScalpLongStartMinute = 885;
		settings.get("MGC").microScalpLongEndMinute = 889;
	}

	private static void applyCurrentBestPlusM2kMesCloseLongStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestSurgicalStack(settings);
		settings.get("M2K").allowCloseMomentumLongs = true;
		settings.get("M2K").closeMomentumLongStartMinute = 900;
		settings.get("M2K").closeMomentumLongEndMinute = 909;
		settings.get("M2K").closeMomentumLongDowWindowSkipMask = 2;
		settings.get("M2K").closeMomentumLongDowWindowSkipStartMinute = 900;
		settings.get("M2K").closeMomentumLongDowWindowSkipEndMinute = 909;
		settings.get("M2K").closeMomentumLongAltEnabled = true;
		settings.get("M2K").closeMomentumLongAltStartMinute = 870;
		settings.get("M2K").closeMomentumLongAltEndMinute = 889;
		settings.get("M2K").closeMomentumLongAltAllowDowMask = 32;
		settings.get("MES").allowCloseMomentumLongs = true;
		settings.get("MES").closeMomentumLongStartMinute = 920;
		settings.get("MES").closeMomentumLongEndMinute = 925;
	}

	private static void applyCurrentBestValueAreaStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestPlusM2kMesCloseLongStack(settings);
		configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 780, 810, false, true);
		configureValueArea(settings.get("M2K"), 6, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 720, 770, false, true);
	}

	private static void applyCurrentBestValueAreaExpandedStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestValueAreaStack(settings);
		settings.get("MES").openingMomentumShortSkipDowMask = 50;
		settings.get("M2K").openingMomentumShortAltSkipDowMask = 14;
	}

	private static void applyCurrentBestValueAreaExpandedMnqStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestValueAreaExpandedStack(settings);
		configureOpeningMomentumShortAlt(settings.get("MNQ"), 651, 654, 60);
	}

	private static void applyCurrentBestValueAreaExpandedMnqMes621Stack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestValueAreaExpandedMnqStack(settings);
		configureOpeningMomentumShortAlt(settings.get("MES"), 621, 621, 26);
	}

	private static void applyCurrentBestShiftAllStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestValueAreaExpandedMnqMes621Stack(settings);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 620, 620, 26);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 623, 623, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 625, 625, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 645, 645, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 648, 648, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 622, 622, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 642, 642, 54);
		configureOpeningMomentumShortOnly(settings.get("MGC"), 10, 15, 0.55, 0.55, 660, 660, 62);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 646, 646, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 637, 639, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 624, 624, 58);
	}

	private static void applyCurrentBestVpbSkipStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestShiftAllStack(settings);
		configureValueArea(settings.get("MES"), 4, 15, 0.70, 4.0, 1.0, 0.30, 0.55, 20.0, 0.0, 720, 810, false, true);
		settings.get("MES").microScalpSkipStartMinute = 858;
		settings.get("MES").microScalpSkipEndMinute = 858;
	}

	private static void applyCurrentBestSurgicalPrunedStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestVpbSkipStack(settings);
		addEinsteinPrunedSurgicalPockets(settings);
	}

	private static void applyCurrentBestBroadPrunedStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestSurgicalPrunedStack(settings);
		addBroadPositivePrunedOmomCells(settings);
	}

	private static void applyCurrentBestSingleWinPrunedStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestBroadPrunedStack(settings);
		addBroadSingleWinnerPrunedOmomCells(settings);
	}

	private static void applyCurrentBestRemainingPrunedStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestSingleWinPrunedStack(settings);
		addRemainingPrunedOmomCells(settings);
	}

	private static void applyCurrentBestLongPositiveStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestRemainingPrunedStack(settings);
		addLongPositiveOmomCells(settings);
	}

	private static void applyCurrentBestLongSecondWaveStack(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		applyCurrentBestLongPositiveStack(settings);
		addLongSecondWaveOmomCells(settings);
	}

	private static void addEinsteinSurgicalPockets(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 633, 633, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 650, 650, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 646, 650, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 630, 640, 54);
	}

	private static void addEinsteinPrunedSurgicalPockets(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 650, 650, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 646, 650, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 630, 634, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 636, 636, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 638, 640, 54);
	}

	private static void addBroadPositiveOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 649, 649, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 632, 632, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 630, 630, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 641, 641, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 621, 621, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 622, 622, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 645, 645, 60);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 640, 640, 60);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 622, 623, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 636, 636, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 627, 627, 54);
	}

	private static void addBroadPositivePrunedOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 649, 649, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 630, 630, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 621, 621, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 645, 645, 60);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 622, 623, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 636, 636, 58);
	}

	private static void addBroadSingleWinnerOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 636, 636, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 647, 647, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 628, 628, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 631, 631, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 632, 632, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 646, 646, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 622, 622, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 626, 626, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 626, 626, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 635, 635, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 625, 625, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 645, 645, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 636, 636, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 636, 636, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 630, 630, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 637, 637, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 643, 643, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 620, 620, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 630, 630, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 636, 636, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 623, 623, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 641, 641, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 639, 639, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 634, 634, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 629, 629, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 623, 623, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 620, 620, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 627, 627, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 625, 625, 60);
	}

	private static void addBroadSingleWinnerPrunedOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 623, 623, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 629, 629, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 620, 620, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 646, 646, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 632, 632, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 647, 647, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 636, 636, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 626, 626, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 631, 631, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 635, 635, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 622, 622, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 625, 625, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 639, 639, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 636, 636, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 623, 623, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 641, 641, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 637, 637, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 630, 630, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 636, 636, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 634, 634, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 620, 620, 54);
	}

	private static void addRemainingPositiveOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 622, 622, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 632, 632, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 647, 647, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 628, 628, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 641, 641, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 621, 621, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 640, 640, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 626, 626, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 625, 625, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 645, 645, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 645, 645, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 620, 620, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 636, 636, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 627, 627, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 643, 643, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 630, 630, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 627, 627, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 649, 649, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 620, 620, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 647, 647, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 633, 633, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 648, 648, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 632, 632, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 640, 640, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 633, 633, 60);
	}

	private static void addRemainingPrunedOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 633, 633, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 650, 650, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 624, 624, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 620, 620, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 620, 620, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 645, 645, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 625, 625, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 626, 626, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 633, 633, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 640, 640, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 621, 621, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 647, 647, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 648, 648, 58);
	}

	private static void addLongPositiveOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		configureOpeningMomentumLongExtrasOnly(settings.get("MES"));
		configureOpeningMomentumLongExtrasOnly(settings.get("MGC"));
		configureOpeningMomentumLongExtrasOnly(settings.get("M2K"));
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 642, 642, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 631, 631, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 621, 621, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 632, 632, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 645, 645, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 621, 621, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 633, 633, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 622, 622, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 627, 627, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 645, 645, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 646, 646, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 644, 644, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 623, 623, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 623, 623, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 630, 630, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 646, 646, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 645, 645, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 622, 622, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 644, 644, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 621, 621, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 645, 645, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 641, 641, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 620, 620, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 628, 628, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 648, 648, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 625, 625, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 648, 648, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 641, 641, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 627, 627, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 621, 621, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 645, 645, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 622, 622, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 628, 628, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 636, 636, 54);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 631, 631, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 644, 644, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 633, 633, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 637, 637, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 639, 639, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 642, 642, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 642, 642, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 645, 645, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 630, 630, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 648, 648, 54);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 631, 631, 58);
	}

	private static void addLongSecondWaveOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 636, 636, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 631, 631, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 620, 620, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 626, 626, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 621, 621, 60);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 629, 629, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 633, 633, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 622, 622, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 636, 636, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 633, 633, 54);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 626, 626, 54);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 626, 626, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 634, 634, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 640, 640, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 633, 633, 30);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 646, 646, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 623, 623, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 631, 631, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 621, 621, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 627, 627, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 621, 621, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 625, 625, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 625, 625, 30);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 647, 647, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 626, 626, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 624, 624, 54);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 632, 632, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 636, 636, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 640, 640, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 631, 631, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 649, 649, 54);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 627, 627, 46);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 621, 621, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 624, 624, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 648, 648, 30);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 646, 646, 46);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 623, 623, 54);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 649, 649, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 647, 647, 46);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 638, 638, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 630, 630, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 627, 627, 46);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 624, 624, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 637, 637, 30);
	}

	private static void addThirdWaveOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumLongExtraWindow(settings.get("MGC"), 644, 644, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 632, 632, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 628, 628, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 641, 641, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 640, 640, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 645, 645, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 636, 636, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 627, 627, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 643, 643, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 630, 630, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 627, 627, 58);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 645, 645, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 632, 632, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 641, 641, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 625, 625, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 646, 646, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 632, 632, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 637, 637, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 641, 641, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 627, 627, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 640, 640, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 642, 642, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 640, 640, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 649, 649, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 628, 628, 30);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 648, 648, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 631, 631, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 624, 624, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 648, 648, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 626, 626, 60);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 626, 626, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 636, 636, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 624, 624, 30);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 624, 624, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 645, 645, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 637, 637, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 649, 649, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 628, 628, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 625, 625, 60);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 640, 640, 46);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 630, 630, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 637, 637, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 645, 645, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 629, 629, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 641, 641, 60);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 649, 649, 60);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 636, 636, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 634, 634, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 644, 644, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 625, 625, 60);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 622, 622, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 634, 634, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 637, 637, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 645, 645, 60);
	}

	private static void addThirdWavePrunedOmomCells(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 641, 641, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 637, 637, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 645, 645, 58);
		addOpeningMomentumShortExtraWindow(settings.get("MGC"), 641, 641, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 625, 625, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 632, 632, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 637, 637, 60);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 627, 627, 46);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 642, 642, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 649, 649, 58);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 628, 628, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 631, 631, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 624, 624, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 626, 626, 60);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 626, 626, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 624, 624, 30);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 624, 624, 60);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 644, 644, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 649, 649, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 628, 628, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 625, 625, 60);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 640, 640, 46);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 637, 637, 54);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 630, 630, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 629, 629, 60);
		addOpeningMomentumLongExtraWindow(settings.get("MES"), 641, 641, 60);
		addOpeningMomentumLongExtraWindow(settings.get("M2K"), 636, 636, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 622, 622, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 637, 637, 58);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 645, 645, 60);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 640, 640, 60);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 636, 636, 54);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 643, 643, 54);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 634, 634, 30);
		addOpeningMomentumShortExtraWindow(settings.get("MES"), 641, 641, 46);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 640, 640, 30);
		addOpeningMomentumShortExtraWindow(settings.get("M2K"), 640, 640, 46);
	}

	private static void configureCloseMomentum(FuturesManager.FuturesStrategySettings settings, int cap, boolean allowLongs, boolean allowShorts, int startMinute, double minMoveTicks, double volumeRatio, double rewardRisk) {
		if (settings == null) {
			return;
		}
		settings.closeMomentum.enabled = true;
		settings.closeMomentum.maxTradesPerDay = cap;
		settings.allowCloseMomentumLongs = allowLongs;
		settings.allowCloseMomentumShorts = allowShorts;
		settings.closeMomentumLongStartMinute = startMinute;
		settings.closeMomentumShortStartMinute = startMinute;
		settings.closeMomentumMinMoveTicks = minMoveTicks;
		settings.closeMomentumVolumeRatio = volumeRatio;
		settings.closeMomentumRewardRisk = rewardRisk;
	}

	private static void configureMicroScalp(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, boolean allowLongs, boolean allowShorts) {
		configureMicroScalp(settings, cap, bucketMinutes, volumeRatio, rewardRisk, maxRiskTicks, minTrendSlopeTicks, allowLongs, allowShorts, 590, 920, 0, 0, 0);
	}

	private static void configureMicroScalp(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, boolean allowLongs, boolean allowShorts, int startMinute, int endMinute, int skipStartMinute, int skipEndMinute) {
		configureMicroScalp(settings, cap, bucketMinutes, volumeRatio, rewardRisk, maxRiskTicks, minTrendSlopeTicks, allowLongs, allowShorts, startMinute, endMinute, skipStartMinute, skipEndMinute, 0);
	}

	private static void configureMicroScalp(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, boolean allowLongs, boolean allowShorts, int startMinute, int endMinute, int skipStartMinute, int skipEndMinute, int skipDowMask) {
		if (settings == null) {
			return;
		}
		settings.microScalp.enabled = true;
		settings.microScalp.maxTradesPerDay = cap;
		settings.allowMicroScalpLongs = allowLongs;
		settings.allowMicroScalpShorts = allowShorts;
		settings.microScalpBucketMinutes = bucketMinutes;
		settings.microScalpMinVolumeRatio = volumeRatio;
		settings.microScalpRewardRisk = rewardRisk;
		settings.microScalpMaxRiskTicks = maxRiskTicks;
		settings.microScalpMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.microScalpMinBodyPct = 18.0;
		settings.microScalpMaxHoldBars = 10;
		settings.microScalpStartMinute = startMinute;
		settings.microScalpEndMinute = endMinute;
		settings.microScalpSkipStartMinute = skipStartMinute;
		settings.microScalpSkipEndMinute = skipEndMinute;
		settings.microScalpSkipDowMask = skipDowMask;
	}

	private static void configureMicroEcho(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double rewardRisk, double maxRiskTicks, double minTrendSlopeTicks, int startMinute, int endMinute, boolean allowLongs, boolean allowShorts, double minRealizedDayPnl, int delayMinutes, int maxDelays) {
		if (settings == null) {
			return;
		}
		settings.microEcho.enabled = true;
		settings.microEcho.maxTradesPerDay = cap;
		settings.allowMicroEchoLongs = allowLongs;
		settings.allowMicroEchoShorts = allowShorts;
		settings.microEchoBucketMinutes = bucketMinutes;
		settings.microEchoMinVolumeRatio = volumeRatio;
		settings.microEchoRewardRisk = rewardRisk;
		settings.microEchoMaxRiskTicks = maxRiskTicks;
		settings.microEchoMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.microEchoMaxHoldBars = 8;
		settings.microEchoStartMinute = startMinute;
		settings.microEchoEndMinute = endMinute;
		settings.microEchoMinRealizedDayPnl = minRealizedDayPnl;
		settings.microEchoDelayMinutes = delayMinutes;
		settings.microEchoMaxDelays = maxDelays;
	}

	private static void enablePdbSweepRefills(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
		nq.priorDayBreakout.enabled = true;
		nq.priorDayBreakout.maxTradesPerDay = 5;
		nq.allowPriorDayBreakoutLongs = false;
		nq.allowPriorDayBreakoutShorts = true;
		nq.priorDayBreakoutMinVolumeRatio = 0.75;

		FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
		mnq.sweep.enabled = true;
		mnq.sweep.maxTradesPerDay = 5;
		mnq.enableLateSweep = true;
		mnq.enableSweepSecondChance = true;
		mnq.earlySweepReclaimTicks = 4.0;
		mnq.lateSweepReclaimTicks = 10.0;
		mnq.lateSweepCloseLocation = 0.35;

		FuturesManager.FuturesStrategySettings mgc = settings.get("MGC");
		mgc.sweep.enabled = true;
		mgc.sweep.maxTradesPerDay = 5;
		mgc.orb.enabled = true;
		mgc.orb.maxTradesPerDay = 3;
	}

	private static void configureKeltnerReversion(FuturesManager.FuturesStrategySettings settings, int cap, double riskTicks, double rewardRisk) {
		configureKeltnerReversionTuned(settings, cap, riskTicks, rewardRisk, 1.45, 0.65, 16.0, 8.0, 15);
	}

	private static void configureKeltnerReversionTuned(FuturesManager.FuturesStrategySettings settings, int cap, double riskTicks, double rewardRisk, double atrMultiplier, double volumeRatio, double bodyPct, double bandWidthTicks, int bucketMinutes) {
		if (settings == null) {
			return;
		}
		settings.keltnerReversion.enabled = true;
		settings.keltnerReversion.maxTradesPerDay = cap;
		settings.keltnerScalp.enabled = false;
		settings.keltnerAtrMultiplier = atrMultiplier;
		settings.keltnerMinVolumeRatio = volumeRatio;
		settings.keltnerMaxRiskTicks = riskTicks;
		settings.keltnerRewardRisk = rewardRisk;
		settings.keltnerMinBodyPct = bodyPct;
		settings.keltnerMinTrendSlopeTicks = 0.5;
		settings.keltnerMinBandWidthTicks = bandWidthTicks;
		settings.keltnerMaxHoldBars = 10;
		settings.keltnerBucketMinutes = bucketMinutes;
	}

	private static void configureOpeningMomentumExpansion(FuturesManager.FuturesStrategySettings settings, int bucketMinutes, double volumeRatio, double rewardRisk) {
		if (settings == null) {
			return;
		}
		settings.openingMomentum.enabled = true;
		settings.openingMomentumBucketMinutes = bucketMinutes;
		settings.openingMomentum.maxTradesPerDay = 10;
		settings.openingMomentumVolumeRatio = volumeRatio;
		settings.openingMomentumLongVolumeRatio = 0.0;
		settings.openingMomentumShortVolumeRatio = 0.0;
		settings.openingMomentumRewardRisk = rewardRisk;
	}

	private static void configureOpeningMomentumShortOnly(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double rewardRisk, int shortStartMinute, int shortEndMinute, int shortSkipDowMask) {
		if (settings == null) {
			return;
		}
		settings.openingMomentum.enabled = true;
		settings.openingMomentum.maxTradesPerDay = cap;
		settings.openingMomentumAllowMultiplePerSide = true;
		settings.openingMomentumRangeMinutes = 10;
		settings.openingMomentumBucketMinutes = bucketMinutes;
		settings.openingMomentumVolumeRatio = volumeRatio;
		settings.openingMomentumLongVolumeRatio = 0.0;
		settings.openingMomentumShortVolumeRatio = 0.0;
		settings.openingMomentumRewardRisk = rewardRisk;
		settings.openingMomentumMaxRiskTicks = Math.min(settings.openingMomentumMaxRiskTicks, 80.0);
		settings.allowOpeningMomentumLongs = false;
		settings.allowOpeningMomentumShorts = true;
		settings.openingMomentumShortStartMinute = shortStartMinute;
		settings.openingMomentumShortEndMinute = shortEndMinute;
		settings.openingMomentumShortSkipStartMinute = 0;
		settings.openingMomentumShortSkipEndMinute = 0;
		settings.openingMomentumShortSkipDowMask = shortSkipDowMask;
	}

	private static void configureOpeningMomentumLongOnly(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double rewardRisk, int longStartMinute, int longEndMinute, int longSkipDowMask) {
		if (settings == null) {
			return;
		}
		settings.openingMomentum.enabled = true;
		settings.openingMomentum.maxTradesPerDay = cap;
		settings.openingMomentumAllowMultiplePerSide = true;
		settings.openingMomentumRangeMinutes = 10;
		settings.openingMomentumBucketMinutes = bucketMinutes;
		settings.openingMomentumVolumeRatio = volumeRatio;
		settings.openingMomentumLongVolumeRatio = 0.0;
		settings.openingMomentumShortVolumeRatio = 0.0;
		settings.openingMomentumRewardRisk = rewardRisk;
		settings.openingMomentumMaxRiskTicks = Math.min(settings.openingMomentumMaxRiskTicks, 80.0);
		settings.allowOpeningMomentumLongs = true;
		settings.allowOpeningMomentumShorts = false;
		settings.openingMomentumLongStartMinute = longStartMinute;
		settings.openingMomentumLongEndMinute = longEndMinute;
		settings.openingMomentumLongSkipStartMinute = 0;
		settings.openingMomentumLongSkipEndMinute = 0;
		settings.openingMomentumLongSkipDowMask = longSkipDowMask;
	}

	private static void configureOpeningMomentumLongExtrasOnly(FuturesManager.FuturesStrategySettings settings) {
		if (settings == null) {
			return;
		}
		settings.openingMomentum.enabled = true;
		settings.openingMomentum.maxTradesPerDay = 10;
		settings.openingMomentumAllowMultiplePerSide = true;
		settings.openingMomentumRangeMinutes = 10;
		settings.openingMomentumBucketMinutes = 15;
		settings.openingMomentumVolumeRatio = 0.55;
		settings.openingMomentumLongVolumeRatio = 0.0;
		settings.openingMomentumShortVolumeRatio = 0.0;
		settings.openingMomentumRewardRisk = 0.55;
		settings.openingMomentumMaxRiskTicks = Math.min(settings.openingMomentumMaxRiskTicks, 80.0);
		settings.allowOpeningMomentumLongs = true;
		settings.openingMomentumLongStartMinute = 570;
		settings.openingMomentumLongEndMinute = 570;
		settings.openingMomentumLongSkipStartMinute = 0;
		settings.openingMomentumLongSkipEndMinute = 0;
		settings.openingMomentumLongSkipDowMask = 62;
		settings.openingMomentumLongExtraWindows = "";
	}

	private static void configureOpeningMomentumShortAlt(FuturesManager.FuturesStrategySettings settings, int shortStartMinute, int shortEndMinute, int shortSkipDowMask) {
		if (settings == null) {
			return;
		}
		settings.openingMomentumShortAltEnabled = true;
		settings.openingMomentumShortAltStartMinute = shortStartMinute;
		settings.openingMomentumShortAltEndMinute = shortEndMinute;
		settings.openingMomentumShortAltSkipDowMask = shortSkipDowMask;
	}

	private static void addOpeningMomentumShortExtraWindow(FuturesManager.FuturesStrategySettings settings, int shortStartMinute, int shortEndMinute, int shortSkipDowMask) {
		if (settings == null) {
			return;
		}
		String window = shortStartMinute + "-" + shortEndMinute + ":" + shortSkipDowMask;
		if (settings.openingMomentumShortExtraWindows == null || settings.openingMomentumShortExtraWindows.trim().isEmpty()) {
			settings.openingMomentumShortExtraWindows = window;
		} else {
			settings.openingMomentumShortExtraWindows = settings.openingMomentumShortExtraWindows + ";" + window;
		}
	}

	private static void addOpeningMomentumLongExtraWindow(FuturesManager.FuturesStrategySettings settings, int longStartMinute, int longEndMinute, int longSkipDowMask) {
		if (settings == null) {
			return;
		}
		String window = longStartMinute + "-" + longEndMinute + ":" + longSkipDowMask;
		if (settings.openingMomentumLongExtraWindows == null || settings.openingMomentumLongExtraWindows.trim().isEmpty()) {
			settings.openingMomentumLongExtraWindows = window;
		} else {
			settings.openingMomentumLongExtraWindows = settings.openingMomentumLongExtraWindows + ";" + window;
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
				insert.setString(2, "FinalTradeGrowthRunner");
				insert.setString(3, name);
				insert.executeUpdate();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void printRankings(List<RunSummary> summaries, boolean verbose, int executed) {
		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				int passCompare = Boolean.compare(passesGoal(second), passesGoal(first));
				if (passCompare != 0) {
					return passCompare;
				}
				if (first.trades != second.trades) {
					return second.trades - first.trades;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		int goalsByTrades = printTop("TOP_GOAL_BY_TRADES", summaries, 25, true);

		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				int passCompare = Boolean.compare(passesGoal(second), passesGoal(first));
				if (passCompare != 0) {
					return passCompare;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		int goalsByPnl = printTop("TOP_GOAL_BY_PNL", summaries, 25, true);

		if (verbose) {
			Collections.sort(summaries, new Comparator<RunSummary>() {
				public int compare(RunSummary first, RunSummary second) {
					if (first.trades != second.trades) {
						return second.trades - first.trades;
					}
					return Double.compare(second.pnl, first.pnl);
				}
			});
			printTop("TOP_OVERALL_BY_TRADES", summaries, 25, false);
		}
		if (goalsByTrades == 0 && goalsByPnl == 0) {
			System.out.println("NO_GOAL_CANDIDATES runs=" + executed
				+ " floorPnl=" + FLOOR_PNL
				+ " floorWin=" + FLOOR_WIN
				+ " floorTrades=" + FLOOR_TRADES);
		}
	}

	private static int printTop(String label, List<RunSummary> summaries, int limit, boolean goalOnly) {
		int printed = 0;
		StringBuilder output = new StringBuilder();
		for (RunSummary summary : summaries) {
			if (printed >= limit) {
				break;
			}
			if (summary.ruleViolation != 0) {
				continue;
			}
			if (goalOnly && !passesGoal(summary)) {
				continue;
			}
			if (printed == 0) {
				output.append(label).append('\n');
			}
			output.append(line(summary)).append('\n');
			printed++;
		}
		if (output.length() > 0) {
			System.out.print(output.toString());
		}
		return printed;
	}

	private static boolean passesGoal(RunSummary summary) {
		return summary.ruleViolation == 0
			&& summary.pnl >= FLOOR_PNL
			&& summary.winRate >= FLOOR_WIN
			&& summary.trades > FLOOR_TRADES;
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
			+ " overlap=" + summary.overlapRejections
			+ " riskReject=" + summary.riskRejections
			+ " goal=" + passesGoal(summary)
			+ " violation=" + summary.ruleViolation
			+ " msg=\"" + (summary.message == null ? "" : summary.message) + "\"";
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static String tag(double value) {
		return String.valueOf(Math.round(value * 100.0));
	}

	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (Exception e) {
			return fallback;
		}
	}
}
