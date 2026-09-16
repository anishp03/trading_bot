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

public class StrictCountGrowthRunner {
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K";
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-04";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final String[] SYMBOL_LIST = new String[] {"MES", "MNQ", "NQ", "MGC", "ES", "M2K"};
	private static final double BASELINE_PNL = 60235.46;
	private static final double BASELINE_RETURN = 120.47;
	private static final double BASELINE_WIN = 66.48;

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
				boolean discoveryRun = scenario.name().startsWith("tradeengine_");
				int id = FuturesManager.generatePortfolioBacktest(
					SYMBOLS,
					START_DATE,
					END_DATE,
					50000.0,
					discoveryRun ? 100000.0 : 2000.0,
					discoveryRun ? 100000.0 : 1000.0,
					700.0,
					50,
					1.24,
					1.0,
					3,
					50,
					5.0,
					true,
					0.0,
					discoveryRun ? "CUSTOM" : FUNDED_PROFILE
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
		values.add(new SimpleScenario("strict_baseline", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {}
		}));

		addOpeningMomentumScenarios(values);
		addFvgQualityScenarios(values);
		addRefillScenarios(values);
		addReversionScenarios(values);
		addKrevGrowthScenarios(values);
		addTradeEngineDiscoveryScenarios(values);
		addComboScenarios(values);
		return values;
	}

	private static void addOpeningMomentumScenarios(List<Scenario> values) {
		final int[] bucketMinutes = new int[] {5, 10, 15, 20};
		final int[] maxTrades = new int[] {5, 7, 10};
		final double[] volumeRatios = new double[] {0.45, 0.50, 0.60, 0.70};
		final double[] rewardRisks = new double[] {0.45, 0.50, 0.55, 0.60, 0.70};
		for (final int bucket : bucketMinutes) {
			for (final int cap : maxTrades) {
				for (final double volume : volumeRatios) {
					for (final double reward : rewardRisks) {
						values.add(new SimpleScenario("omom_b" + bucket + "_cap" + cap + "_v" + tag(volume) + "_rr" + tag(reward), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
								mnq.openingMomentumBucketMinutes = bucket;
								mnq.openingMomentum.maxTradesPerDay = cap;
								mnq.openingMomentumVolumeRatio = volume;
								mnq.openingMomentumRewardRisk = reward;
							}
						}));
					}
				}
			}
		}
	}

	private static void addFvgQualityScenarios(List<Scenario> values) {
		final int[] startMinutes = new int[] {600, 630, 690, 720};
		final int[] endMinutes = new int[] {779, 839, 900};
		final double[] volumeRatios = new double[] {0.6, 0.8, 1.0, 1.2, 1.5};
		final double[] minWidths = new double[] {4.0, 6.0, 8.0, 10.0};
		final double[] maxRisks = new double[] {28.0, 36.0, 48.0};
		for (final int start : startMinutes) {
			for (final int end : endMinutes) {
				if (end < start) {
					continue;
				}
				for (final double volume : volumeRatios) {
					for (final double minWidth : minWidths) {
						for (final double maxRisk : maxRisks) {
							values.add(new SimpleScenario("nq_fvg_s" + start + "_e" + end + "_v" + tag(volume) + "_w" + tag(minWidth) + "_r" + tag(maxRisk), new ScenarioApplier() {
								public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
									FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
									nq.fvg.enabled = true;
									nq.fvg.maxTradesPerDay = 5;
									nq.fvgStartMinute = start;
									nq.fvgEndMinute = end;
									nq.fvgSkipStartMinute = 0;
									nq.fvgSkipEndMinute = 0;
									nq.fvgMinVolumeRatio = volume;
									nq.fvgMinWidthTicks = minWidth;
									nq.fvgMaxRiskTicks = maxRisk;
									nq.fvgRewardRisk = 1.0;
								}
							}));
						}
					}
				}
			}
		}
	}

	private static void addRefillScenarios(List<Scenario> values) {
		values.add(new SimpleScenario("refill_highwin_keep_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableHighWinRefills(settings);
			}
		}));
		values.add(new SimpleScenario("refill_cmom_only_keep_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enableCmomRefills(settings);
			}
		}));
		values.add(new SimpleScenario("refill_pdb_sweep_keep_targets", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enablePdbSweepRefills(settings);
			}
		}));
		values.add(new SimpleScenario("refill_mnq_more_sweep_pdb", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
				mnq.sweep.enabled = true;
				mnq.sweep.maxTradesPerDay = 8;
				mnq.priorDayBreakout.enabled = true;
				mnq.priorDayBreakout.maxTradesPerDay = 6;
			}
		}));
	}

	private static void addReversionScenarios(List<Scenario> values) {
		final String[] symbols = new String[] {"MES", "MNQ", "MGC", "M2K", "ES", "NQ"};
		final int[] caps = new int[] {2, 4, 6};
		final double[] keltnerRisks = new double[] {10.0, 14.0, 18.0, 22.0};
		final double[] keltnerRewards = new double[] {0.45, 0.60, 0.75, 0.85};
		for (final String symbol : symbols) {
			for (final int cap : caps) {
				for (final double risk : keltnerRisks) {
					for (final double reward : keltnerRewards) {
						values.add(new SimpleScenario("krev_" + symbol.toLowerCase() + "_cap" + cap + "_r" + tag(risk) + "_rr" + tag(reward), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								configureKeltnerReversion(settings.get(symbol), cap, risk, reward);
							}
						}));
						values.add(new SimpleScenario("strictrefill_krev_" + symbol.toLowerCase() + "_cap" + cap + "_r" + tag(risk) + "_rr" + tag(reward), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								enablePdbSweepRefills(settings);
								configureKeltnerReversion(settings.get(symbol), cap, risk, reward);
							}
						}));
					}
				}
			}
		}

		final double[] distances = new double[] {18.0, 24.0, 30.0, 36.0, 48.0};
		final double[] oversold = new double[] {25.0, 30.0, 35.0};
		for (final String symbol : symbols) {
			for (final int cap : caps) {
				for (final double distance : distances) {
					for (final double rsi : oversold) {
						values.add(new SimpleScenario("mrvwap_" + symbol.toLowerCase() + "_cap" + cap + "_d" + tag(distance) + "_rsi" + tag(rsi), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								configureMeanReversion(settings.get(symbol), cap, distance, rsi);
							}
						}));
						values.add(new SimpleScenario("strictrefill_mrvwap_" + symbol.toLowerCase() + "_cap" + cap + "_d" + tag(distance) + "_rsi" + tag(rsi), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								enablePdbSweepRefills(settings);
								configureMeanReversion(settings.get(symbol), cap, distance, rsi);
							}
						}));
					}
				}
			}
		}
	}

	private static void addComboScenarios(List<Scenario> values) {
		final int[] buckets = new int[] {5, 10};
		final double[] omomRewards = new double[] {0.45, 0.50, 0.55, 0.60};
		final double[] fvgVolumes = new double[] {0.8, 1.0, 1.2};
		final double[] fvgWidths = new double[] {6.0, 8.0, 10.0};
		for (final int bucket : buckets) {
			for (final double omomReward : omomRewards) {
				for (final double fvgVolume : fvgVolumes) {
					for (final double fvgWidth : fvgWidths) {
						values.add(new SimpleScenario("combo_omom_b" + bucket + "_rr" + tag(omomReward) + "_fvg_v" + tag(fvgVolume) + "_w" + tag(fvgWidth), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								FuturesManager.FuturesStrategySettings mnq = settings.get("MNQ");
								mnq.openingMomentumBucketMinutes = bucket;
								mnq.openingMomentum.maxTradesPerDay = 10;
								mnq.openingMomentumRewardRisk = omomReward;

								FuturesManager.FuturesStrategySettings nq = settings.get("NQ");
								nq.fvg.enabled = true;
								nq.fvg.maxTradesPerDay = 5;
								nq.fvgStartMinute = 600;
								nq.fvgEndMinute = 900;
								nq.fvgSkipStartMinute = 660;
								nq.fvgSkipEndMinute = 719;
								nq.fvgMinVolumeRatio = fvgVolume;
								nq.fvgMinWidthTicks = fvgWidth;
								nq.fvgRewardRisk = 1.0;
								enableHighWinRefills(settings);
							}
						}));
					}
				}
			}
		}
	}

	private static void addTradeEngineDiscoveryScenarios(List<Scenario> values) {
		values.add(new SimpleScenario("tradeengine_tlad_all_dense", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					configureTrendLadder(settings.get(symbol), 20, 6, 0.25, 36.0, 0.50, 12.0, 0.0, 600, 920, 14, false);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_all_balanced", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					configureTrendLadder(settings.get(symbol), 16, 10, 0.35, 28.0, 0.60, 10.0, 0.5, 615, 900, 12, false);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_all_quality", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					configureTrendLadder(settings.get(symbol), 12, 12, 0.45, 22.0, 0.75, 8.0, 1.0, 630, 885, 12, true);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_micro_dense", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "MNQ", "MGC", "M2K"}) {
					configureTrendLadder(settings.get(symbol), 24, 5, 0.20, 40.0, 0.45, 14.0, 0.0, 600, 920, 12, false);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_index_dense", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "MNQ", "ES", "NQ"}) {
					configureTrendLadder(settings.get(symbol), 24, 5, 0.20, 40.0, 0.45, 14.0, 0.0, 600, 920, 12, false);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_short_bias_dense", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					configureTrendLadder(settings.get(symbol), 20, 6, 0.25, 36.0, 0.55, 12.0, 0.0, 600, 920, 14, false);
					settings.get(symbol).allowTrendLadderLongs = false;
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_long_bias_dense", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					configureTrendLadder(settings.get(symbol), 20, 6, 0.25, 36.0, 0.55, 12.0, 0.0, 600, 920, 14, false);
					settings.get(symbol).allowTrendLadderShorts = false;
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_plus_strictrefill", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enablePdbSweepRefills(settings);
				for (String symbol : SYMBOL_LIST) {
					configureTrendLadder(settings.get(symbol), 16, 8, 0.30, 32.0, 0.55, 12.0, 0.25, 600, 915, 12, false);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_index_tight8", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "MNQ", "ES", "NQ"}) {
					configureTrendLadder(settings.get(symbol), 20, 8, 0.35, 8.0, 0.85, 6.0, 0.75, 600, 900, 8, false);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_tlad_index_tight12", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "MNQ", "ES", "NQ"}) {
					configureTrendLadder(settings.get(symbol), 18, 10, 0.45, 12.0, 0.95, 5.0, 1.0, 615, 885, 10, true);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_mscalp_all_dense", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					configureMicroScalp(settings.get(symbol), 22, 5, 0.25, 16.0, 0.75, 10.0, 0.0, 8);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_mscalp_index_tight", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "MNQ", "ES", "NQ"}) {
					configureMicroScalp(settings.get(symbol), 20, 5, 0.35, 10.0, 0.85, 12.0, 0.25, 8);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_mscalp_micro_quality", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "MNQ", "MGC", "M2K"}) {
					configureMicroScalp(settings.get(symbol), 14, 10, 0.55, 14.0, 0.95, 18.0, 0.75, 10);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_kelt_breakout_all_dense", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					configureKeltnerScalp(settings.get(symbol), 20, 5, 1.00, 0.30, 16.0, 0.75, 10.0, 0.0, 6.0, 8, false);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_kelt_breakout_index_tight", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : new String[] {"MES", "MNQ", "ES", "NQ"}) {
					configureKeltnerScalp(settings.get(symbol), 18, 6, 1.15, 0.45, 12.0, 0.90, 14.0, 0.5, 8.0, 8, false);
				}
			}
		}));
		values.add(new SimpleScenario("tradeengine_kelt_breakout_quality", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				for (String symbol : SYMBOL_LIST) {
					configureKeltnerScalp(settings.get(symbol), 12, 10, 1.30, 0.65, 14.0, 1.05, 18.0, 0.75, 10.0, 10, true);
				}
			}
		}));
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

	private static void configureMicroScalp(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double volumeRatio, double maxRiskTicks, double rewardRisk, double bodyPct, double minTrendSlopeTicks, int maxHoldBars) {
		if (settings == null) {
			return;
		}
		settings.microScalp.enabled = true;
		settings.microScalp.maxTradesPerDay = cap;
		settings.microScalpBucketMinutes = bucketMinutes;
		settings.microScalpMinVolumeRatio = volumeRatio;
		settings.microScalpMaxRiskTicks = maxRiskTicks;
		settings.microScalpRewardRisk = rewardRisk;
		settings.microScalpMinBodyPct = bodyPct;
		settings.microScalpMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.microScalpMaxHoldBars = maxHoldBars;
		settings.requireHigherTimeframeGuard = false;
	}

	private static void configureKeltnerScalp(FuturesManager.FuturesStrategySettings settings, int cap, int bucketMinutes, double atrMultiplier, double volumeRatio, double maxRiskTicks, double rewardRisk, double bodyPct, double minTrendSlopeTicks, double bandWidthTicks, int maxHoldBars, boolean requireHigherTimeframeGuard) {
		if (settings == null) {
			return;
		}
		settings.keltnerScalp.enabled = true;
		settings.keltnerReversion.enabled = false;
		settings.keltnerScalp.maxTradesPerDay = cap;
		settings.keltnerBucketMinutes = bucketMinutes;
		settings.keltnerAtrMultiplier = atrMultiplier;
		settings.keltnerMinVolumeRatio = volumeRatio;
		settings.keltnerMaxRiskTicks = maxRiskTicks;
		settings.keltnerRewardRisk = rewardRisk;
		settings.keltnerMinBodyPct = bodyPct;
		settings.keltnerMinTrendSlopeTicks = minTrendSlopeTicks;
		settings.keltnerMinBandWidthTicks = bandWidthTicks;
		settings.keltnerMaxHoldBars = maxHoldBars;
		settings.allowKeltnerScalpLongs = true;
		settings.allowKeltnerScalpShorts = true;
		settings.requireHigherTimeframeGuard = requireHigherTimeframeGuard;
	}

	private static void addKrevGrowthScenarios(List<Scenario> values) {
		values.add(new SimpleScenario("strictrefill_krev_dual_nq_mnq_best", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enablePdbSweepRefills(settings);
				configureKeltnerReversion(settings.get("NQ"), 6, 22.0, 0.85);
				configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
			}
		}));
		values.add(new SimpleScenario("strictrefill_krev_dual_nqatr135_mnq_best", new ScenarioApplier() {
			public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
				enablePdbSweepRefills(settings);
				configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, 1.35, 0.65, 16.0, 8.0, 15);
				configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
			}
		}));

		final double[] omomVolumes = new double[] {0.45};
		final double[] omomRewards = new double[] {0.45, 0.50, 0.55, 0.60, 0.70};
		for (final double omomVolume : omomVolumes) {
			for (final double omomReward : omomRewards) {
				values.add(new SimpleScenario("strictrefill_krev_nq_omom_b5_v" + tag(omomVolume) + "_rr" + tag(omomReward), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
						enablePdbSweepRefills(settings);
						configureKeltnerReversion(settings.get("NQ"), 6, 22.0, 0.85);
						configureOpeningMomentumExpansion(settings.get("MNQ"), 5, omomVolume, omomReward);
					}
				}));
				values.add(new SimpleScenario("strictrefill_krev_dual_omom_b5_v" + tag(omomVolume) + "_rr" + tag(omomReward), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
						enablePdbSweepRefills(settings);
						configureKeltnerReversion(settings.get("NQ"), 6, 22.0, 0.85);
						configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
						configureOpeningMomentumExpansion(settings.get("MNQ"), 5, omomVolume, omomReward);
					}
				}));
				values.add(new SimpleScenario("strictrefill_krev_dual_nqatr135_omom_b5_v" + tag(omomVolume) + "_rr" + tag(omomReward), new ScenarioApplier() {
					public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
						enablePdbSweepRefills(settings);
						configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, 1.35, 0.65, 16.0, 8.0, 15);
						configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
						configureOpeningMomentumExpansion(settings.get("MNQ"), 5, omomVolume, omomReward);
					}
				}));
			}
		}

		final int[] omomBuckets = new int[] {3, 5, 10, 15, 20};
		final double[] gridVolumes = new double[] {0.40, 0.45, 0.50};
		final double[] gridRewards = new double[] {0.45, 0.50, 0.55};
		final double[] nqAtrMultipliers = new double[] {1.35, 1.45};
		for (final double nqAtr : nqAtrMultipliers) {
			for (final int bucket : omomBuckets) {
				for (final double volume : gridVolumes) {
					for (final double reward : gridRewards) {
						values.add(new SimpleScenario("strictrefill_krev_omomgrid_nqatr" + tag(nqAtr) + "_b" + bucket + "_v" + tag(volume) + "_rr" + tag(reward), new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								enablePdbSweepRefills(settings);
								configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, nqAtr, 0.65, 16.0, 8.0, 15);
								configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
								configureOpeningMomentumExpansion(settings.get("MNQ"), bucket, volume, reward);
							}
						}));
					}
				}
			}
		}

		final double[] splitLongVolumes = new double[] {0.45, 0.50};
		final double[] splitShortVolumes = new double[] {0.35, 0.40, 0.45};
		final int[] splitSkipMasks = new int[] {0, 12, 28};
		for (final double nqAtr : nqAtrMultipliers) {
			for (final int bucket : new int[] {3, 5, 15}) {
				for (final double longVolume : splitLongVolumes) {
					for (final double shortVolume : splitShortVolumes) {
						for (final int shortSkipMask : splitSkipMasks) {
							values.add(new SimpleScenario("strictrefill_krev_omomsplit_nqatr" + tag(nqAtr) + "_b" + bucket + "_lv" + tag(longVolume) + "_sv" + tag(shortVolume) + "_ss" + shortSkipMask, new ScenarioApplier() {
								public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
									enablePdbSweepRefills(settings);
									configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, nqAtr, 0.65, 16.0, 8.0, 15);
									configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
									configureOpeningMomentumSplit(settings.get("MNQ"), bucket, longVolume, shortVolume, 0.45, shortSkipMask);
								}
							}));
						}
					}
				}
			}
		}
		for (final double nqAtr : nqAtrMultipliers) {
			for (final int bucket : new int[] {3, 5, 15}) {
				for (final double longVolume : splitLongVolumes) {
					for (final double shortVolume : new double[] {0.40, 0.45}) {
						for (final int shortEndMinute : new int[] {642, 646}) {
							values.add(new SimpleScenario("strictrefill_krev_omomcut_nqatr" + tag(nqAtr) + "_b" + bucket + "_lv" + tag(longVolume) + "_sv" + tag(shortVolume) + "_e" + shortEndMinute, new ScenarioApplier() {
								public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
									enablePdbSweepRefills(settings);
									configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, nqAtr, 0.65, 16.0, 8.0, 15);
									configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
									configureOpeningMomentumSplit(settings.get("MNQ"), bucket, longVolume, shortVolume, 0.45, 0);
									settings.get("MNQ").openingMomentumShortEndMinute = shortEndMinute;
								}
							}));
							values.add(new SimpleScenario("strictrefill_krev_omomcutskip_nqatr" + tag(nqAtr) + "_b" + bucket + "_lv" + tag(longVolume) + "_sv" + tag(shortVolume) + "_e" + shortEndMinute, new ScenarioApplier() {
								public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
									enablePdbSweepRefills(settings);
									configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, nqAtr, 0.65, 16.0, 8.0, 15);
									configureKeltnerReversion(settings.get("MNQ"), 6, 22.0, 0.85);
									configureOpeningMomentumSplit(settings.get("MNQ"), bucket, longVolume, shortVolume, 0.45, 0);
									settings.get("MNQ").openingMomentumShortSkipStartMinute = 621;
									settings.get("MNQ").openingMomentumShortSkipEndMinute = 623;
									settings.get("MNQ").openingMomentumShortEndMinute = shortEndMinute;
								}
							}));
						}
					}
				}
			}
		}

		final double[] atrMultipliers = new double[] {1.25, 1.35, 1.45, 1.60};
		final double[] volumeRatios = new double[] {0.45, 0.55, 0.65};
		final double[] bodyPcts = new double[] {10.0, 14.0, 16.0};
		final int[] buckets = new int[] {5, 10, 15};
		for (final double atr : atrMultipliers) {
			for (final double volume : volumeRatios) {
				for (final double body : bodyPcts) {
					for (final int bucket : buckets) {
						values.add(new SimpleScenario("strictrefill_nqkrev_tune_atr" + tag(atr) + "_v" + tag(volume) + "_body" + tag(body) + "_b" + bucket, new ScenarioApplier() {
							public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
								enablePdbSweepRefills(settings);
								configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, atr, volume, body, 8.0, bucket);
							}
						}));
					}
				}
			}
		}

		final double[] bandWidths = new double[] {4.0, 6.0, 8.0, 10.0, 12.0};
		for (final double bandWidth : bandWidths) {
			values.add(new SimpleScenario("strictrefill_nqkrev_band" + tag(bandWidth), new ScenarioApplier() {
				public void apply(Map<String, FuturesManager.FuturesStrategySettings> settings, Map<String, FuturesManager.FuturesRiskSettings> risks) {
					enablePdbSweepRefills(settings);
					configureKeltnerReversionTuned(settings.get("NQ"), 8, 22.0, 0.85, 1.45, 0.65, 16.0, bandWidth, 15);
				}
			}));
		}
	}

	private static void enableHighWinRefills(Map<String, FuturesManager.FuturesStrategySettings> settings) {
		enableCmomRefills(settings);
		enablePdbSweepRefills(settings);
	}

	private static void enableCmomRefills(Map<String, FuturesManager.FuturesStrategySettings> settings) {
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
		if (settings == null) {
			return;
		}
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
		settings.openingMomentumBucketMinutes = bucketMinutes;
		settings.openingMomentum.maxTradesPerDay = 10;
		settings.openingMomentumVolumeRatio = volumeRatio;
		settings.openingMomentumLongVolumeRatio = 0.0;
		settings.openingMomentumShortVolumeRatio = 0.0;
		settings.openingMomentumRewardRisk = rewardRisk;
	}

	private static void configureOpeningMomentumSplit(FuturesManager.FuturesStrategySettings settings, int bucketMinutes, double longVolumeRatio, double shortVolumeRatio, double rewardRisk, int shortSkipDowMask) {
		if (settings == null) {
			return;
		}
		settings.openingMomentumBucketMinutes = bucketMinutes;
		settings.openingMomentum.maxTradesPerDay = 10;
		settings.openingMomentumVolumeRatio = Math.min(longVolumeRatio, shortVolumeRatio);
		settings.openingMomentumLongVolumeRatio = longVolumeRatio;
		settings.openingMomentumShortVolumeRatio = shortVolumeRatio;
		settings.openingMomentumRewardRisk = rewardRisk;
		settings.openingMomentumShortSkipDowMask = shortSkipDowMask;
	}

	private static void configureMeanReversion(FuturesManager.FuturesStrategySettings settings, int cap, double distanceTicks, double rsiThreshold) {
		if (settings == null) {
			return;
		}
		settings.vwapMeanReversion.enabled = true;
		settings.vwapMeanReversion.maxTradesPerDay = cap;
		settings.meanReversionMinDistanceTicks = distanceTicks;
		settings.meanReversionOversoldRsi = rsiThreshold;
		settings.meanReversionOverboughtRsi = 100.0 - rsiThreshold;
		settings.minRewardRisk = Math.min(settings.minRewardRisk, 1.15);
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
				insert.setString(2, "StrictCountGrowthRunner");
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
				int guardCompare = Boolean.compare(passesStrictGuard(second), passesStrictGuard(first));
				if (guardCompare != 0) {
					return guardCompare;
				}
				if (first.trades != second.trades) {
					return second.trades - first.trades;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_STRICT_BY_TRADES");
		printTop(summaries, 20, true);

		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				int guardCompare = Boolean.compare(passesStrictGuard(second), passesStrictGuard(first));
				if (guardCompare != 0) {
					return guardCompare;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_STRICT_BY_PNL");
		printTop(summaries, 20, true);

		Collections.sort(summaries, new Comparator<RunSummary>() {
			public int compare(RunSummary first, RunSummary second) {
				int tradeCompare = second.trades - first.trades;
				if (tradeCompare != 0) {
					return tradeCompare;
				}
				return Double.compare(second.pnl, first.pnl);
			}
		});
		System.out.println("TOP_OVERALL_BY_TRADES");
		printTop(summaries, 20, false);
	}

	private static void printTop(List<RunSummary> summaries, int limit, boolean strictOnly) {
		int printed = 0;
		for (RunSummary summary : summaries) {
			if (printed >= limit) {
				break;
			}
			if (summary.ruleViolation != 0) {
				continue;
			}
			if (strictOnly && !passesStrictGuard(summary)) {
				continue;
			}
			System.out.println(line(summary));
			printed++;
		}
	}

	private static boolean passesStrictGuard(RunSummary summary) {
		return summary.ruleViolation == 0
			&& summary.pnl >= BASELINE_PNL
			&& summary.returnPct >= BASELINE_RETURN
			&& summary.winRate >= BASELINE_WIN;
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
			+ " strict=" + passesStrictGuard(summary)
			+ " violation=" + summary.ruleViolation
			+ " msg=\"" + (summary.message == null ? "" : summary.message) + "\"";
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static String tag(double value) {
		return String.valueOf(Math.round(value * 100.0));
	}
}
