package com.tradingbot;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WindowStrategyResearchRunner {
	private static final String RUNNER = "WindowStrategyResearchRunner";
	private static final String RESEARCH_RELAXED_WINDOWS_PROPERTY = "tradingbot.research.relaxedWindows";
	private static final String BASE_PRESET = "94k";
	private static final String SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final List<String> SYMBOL_LIST = Arrays.asList("MES", "MNQ", "NQ", "MGC", "ES", "M2K", "MYM", "MCL");
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-22";
	private static final String FUNDED_PROFILE = "TOPSTEP_50K_RESEARCH";
	private static final double ACCOUNT_SIZE = 50000.0;
	private static final double MAX_TRAILING_DRAWDOWN = 2000.0;
	private static final double DAILY_LOSS_LIMIT = 1000.0;
	private static final double MAX_RISK_PER_TRADE = 700.0;
	private static final int MAX_CONTRACTS = 50;
	private static final double COMMISSION_PER_CONTRACT = 1.24;
	private static final double SLIPPAGE_TICKS = 1.0;
	private static final int MAX_OPEN_POSITIONS = 3;
	private static final int MAX_AGGREGATE_CONTRACTS = 50;
	private static final double MAX_AGGREGATE_MINI_UNITS = 5.0;
	private static final String RUN_TAG = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

	private interface SettingsModifier {
		void apply(String symbol, FuturesManager.FuturesStrategySettings settings);
	}

	private static final class RunSummary {
		private String scenario;
		private String description;
		private String symbols;
		private String preset;
		private int id;
		private double pnl;
		private int trades;
		private double winRate;
		private double profitFactor;
		private double avgPnl;
		private double maxDrawdownPct;
		private double maxIntradayLoss;
		private int dailyLossBreaches;
		private int trailingDrawdownBreaches;
		private int maeBreaches;
		private int ruleViolation;
		private String ruleMessage;
	}

	private static final class GroupMetrics {
		private String key;
		private int trades;
		private double pnl;
		private double avgPnl;
		private double winRate;
		private double profitFactor;
		private double avgWin;
		private double avgLoss;
		private double maxDrawdown;
		private double avgHoldMinutes;
		private double avgMfe;
		private double avgMae;
		private double targetRate;
		private double stopRate;
	}

	public static void main(String[] args) throws Exception {
		Path backendDir = Paths.get("").toAbsolutePath();
		Path sourceDb = args.length > 0
			? Paths.get(args[0]).toAbsolutePath()
			: backendDir.resolve("tradingbot.db").toAbsolutePath();
		Path outputDir = backendDir.resolve("target/window-strategy-analysis");
		Files.createDirectories(outputDir);
		Path analysisDb = outputDir.resolve("tradingbot-window-analysis-" + RUN_TAG + ".db");
		Files.copy(sourceDb, analysisDb, StandardCopyOption.REPLACE_EXISTING);
		File sourceWal = new File(sourceDb.toString() + "-wal");
		if (sourceWal.exists() && sourceWal.length() > 0L) {
			Files.copy(sourceWal.toPath(), Paths.get(analysisDb.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
		}
		System.setProperty("tradingbot.db.path", analysisDb.toString());
		System.setProperty("tradingbot.futuresDataDir", backendDir.resolve("market_data/futures").toString());
		FuturesManager.initializeStore();

		StringBuilder report = new StringBuilder();
		report.append("# Strategy Window Research Report\n\n");
		report.append("- Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" local\n");
		report.append("- Source preset: `").append(BASE_PRESET).append("`\n");
		report.append("- Symbols: `").append(SYMBOLS).append("`\n");
		report.append("- Backtest range: `").append(START_DATE).append("` to `").append(END_DATE).append("`\n");
		report.append("- Analysis DB copy: `").append(analysisDb).append("`\n\n");
		report.append("This runner copies the dev SQLite DB and writes all research presets/backtests into the copy. It does not mutate live_backend, the dev runtime DB, or saved strategy presets.\n\n");

		appendWindowInventory(report);
		appendStrategyTaxonomy(report);

		List<RunSummary> portfolioRuns = new ArrayList<RunSummary>();
		portfolioRuns.add(runExistingPreset("baseline_current_94k", "Current hard-window baseline.", BASE_PRESET, SYMBOLS));
		portfolioRuns.add(runVariant("portfolio_unnecessary_windows_removed", "Research-only removal of non-time-native candidate windows: FVG/PDB/VWAP/VRCL/SWEEP/KELT/KREV/VPB/MSCALP/SHDW/ECHO/WFT. ORB/OMOM/CMOM/AFT/LORB/MIM keep their native time thesis.", SYMBOLS, new SettingsModifier() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				relaxUnnecessaryWindowCandidates(settings);
			}
		}, hardWindowCandidateCodes()));
		portfolioRuns.add(runVariant("portfolio_omom_extra_windows_removed", "Clears learned OMOM extra micro-windows but keeps the current primary OMOM windows.", SYMBOLS, new SettingsModifier() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				removeOmomExtraWindows(settings);
			}
		}));
		portfolioRuns.add(runVariant("portfolio_omom_natural_open", "Converts OMOM to natural open-session windows and removes learned extra micro-windows.", SYMBOLS, new SettingsModifier() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				relaxOpeningMomentumToNaturalOpen(settings);
			}
		}));
		portfolioRuns.add(runVariant("portfolio_pattern_windows_broadened", "Broadens suspect pattern/level strategy windows while leaving open/close-native strategies alone.", SYMBOLS, new SettingsModifier() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				relaxPatternLevelWindows(settings);
			}
		}));
		portfolioRuns.add(runVariant("portfolio_suspect_windows_removed", "Combines pattern/level broadening with natural OMOM open windows.", SYMBOLS, new SettingsModifier() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				relaxPatternLevelWindows(settings);
				relaxOpeningMomentumToNaturalOpen(settings);
			}
		}));
		portfolioRuns.add(runVariant("portfolio_low_value_micro_disabled", "Disables low-output micro add-ons: MSCALP, ECHO, WFT, and VPB.", SYMBOLS, new SettingsModifier() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				settings.microScalp.enabled = false;
				settings.microEcho.enabled = false;
				settings.winnerFollowThrough.enabled = false;
				settings.valueAreaReclaim.enabled = false;
			}
		}));
		portfolioRuns.add(runVariant("portfolio_core_quality_trim", "Keeps the main productive families and removes experimental micro add-ons plus IPB/VRCL.", SYMBOLS, new SettingsModifier() {
			@Override
			public void apply(String symbol, FuturesManager.FuturesStrategySettings settings) {
				settings.microScalp.enabled = false;
				settings.microShadow.enabled = false;
				settings.microEcho.enabled = false;
				settings.winnerFollowThrough.enabled = false;
				settings.valueAreaReclaim.enabled = false;
				settings.vwapReclaim.enabled = false;
				settings.marketIntradayMomentum.enabled = false;
			}
		}));

		report.append("## Portfolio Sweep\n\n");
		appendRunTable(report, portfolioRuns);
		appendRunDeltas(report, portfolioRuns);
		appendBreakdownSection(report, portfolioRuns.get(0).id, "Baseline Current 94k");
		for (int index = 1; index < portfolioRuns.size(); index++) {
			appendCompactComparison(report, portfolioRuns.get(index));
		}

		List<RunSummary> isolatedRuns = new ArrayList<RunSummary>();
		runIsolatedStrategySweeps(isolatedRuns);
		report.append("## Isolated Window Sweeps\n\n");
		report.append("These single-symbol sweeps disable other toggles for that symbol, then compare the current strategy window against a relaxed/natural-window version when that can be expressed through existing settings. Dependent add-ons such as SHDW/ECHO/WFT are better judged from the portfolio run because they depend on other source signals.\n\n");
		appendRunTable(report, isolatedRuns);
		appendIsolatedVerdicts(report, isolatedRuns);

		appendRecommendation(report, portfolioRuns, isolatedRuns);

		Path reportPath = outputDir.resolve("window-strategy-analysis-" + RUN_TAG + ".md");
		Files.write(reportPath, report.toString().getBytes("UTF-8"));
		System.out.println("REPORT=" + reportPath);
		System.out.println("ANALYSIS_DB=" + analysisDb);
		for (RunSummary run : portfolioRuns) {
			System.out.println(run.scenario + " id=" + run.id + " pnl=" + round(run.pnl) + " trades=" + run.trades + " win=" + round(run.winRate) + " pf=" + round(run.profitFactor));
		}
	}

	private static void appendWindowInventory(StringBuilder report) {
		report.append("## Active Window Inventory\n\n");
		report.append("| Symbol | Enabled strategy codes | Heavy/suspect windows observed |\n");
		report.append("|---|---|---|\n");
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(BASE_PRESET));
			report.append("| ").append(symbol).append(" | `").append(join(enabledCodes(settings), "`, `")).append("` | ");
			report.append(windowConcerns(settings)).append(" |\n");
		}
		report.append("\n");
	}

	private static void appendStrategyTaxonomy(StringBuilder report) {
		report.append("## Window Taxonomy Used For This Run\n\n");
		report.append("- Time-native windows kept as strategy thesis: `ORB`, `ORB2`, `LORB`, `OMOM`, `CMOM`, `AFT`, `MIM`, `IPB`.\n");
		report.append("- Candidate windows removed for research: `SWEEP`, `PDB`, `VWAP`, `VRCL`, `FVG`, `KREV`, `KELT`, `MRVWAP`, `VPB`, `MSCALP`, `SHDW`, `ECHO`, `WFT`.\n");
		report.append("- Dependent add-ons (`SHDW`, `ECHO`, `WFT`) are judged from portfolio contribution rather than isolated runs because they need source strategy signals.\n\n");
	}

	private static RunSummary runExistingPreset(String scenario, String description, String preset, String symbols) throws Exception {
		int id = runBacktest(symbols, preset);
		labelRun(id, scenario);
		RunSummary summary = loadRunSummary(id);
		summary.scenario = scenario;
		summary.description = description;
		summary.symbols = symbols;
		summary.preset = preset;
		return summary;
	}

	private static RunSummary runVariant(String scenario, String description, String symbols, SettingsModifier modifier) throws Exception {
		return runVariant(scenario, description, symbols, modifier, "");
	}

	private static RunSummary runVariant(String scenario, String description, String symbols, SettingsModifier modifier, String relaxedHardWindowCodes) throws Exception {
		String preset = "analysis_" + compactName(scenario) + "_" + RUN_TAG;
		FuturesManager.createStrategyPreset(preset, BASE_PRESET);
		String slot = FuturesManager.strategyPresetSlot(preset);
		for (String symbol : SYMBOL_LIST) {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, slot);
			modifier.apply(symbol, settings);
			FuturesManager.saveFuturesStrategySettings(symbol, slot, settings);
		}
		int id = runBacktest(symbols, preset, relaxedHardWindowCodes);
		labelRun(id, scenario);
		RunSummary summary = loadRunSummary(id);
		summary.scenario = scenario;
		summary.description = description;
		summary.symbols = symbols;
		summary.preset = preset;
		return summary;
	}

	private static int runBacktest(String symbols, String preset) {
		return runBacktest(symbols, preset, "");
	}

	private static int runBacktest(String symbols, String preset, String relaxedHardWindowCodes) {
		String previous = System.getProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY);
		boolean relaxed = relaxedHardWindowCodes != null && !relaxedHardWindowCodes.trim().isEmpty();
		if (relaxed) {
			System.setProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY, relaxedHardWindowCodes);
		} else {
			System.clearProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY);
		}
		try {
			return generateBacktest(symbols, preset);
		} finally {
			if (previous == null) {
				System.clearProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY);
			} else {
				System.setProperty(RESEARCH_RELAXED_WINDOWS_PROPERTY, previous);
			}
		}
	}

	private static int generateBacktest(String symbols, String preset) {
		return FuturesManager.generatePortfolioBacktest(
			symbols,
			START_DATE,
			END_DATE,
			ACCOUNT_SIZE,
			MAX_TRAILING_DRAWDOWN,
			DAILY_LOSS_LIMIT,
			MAX_RISK_PER_TRADE,
			MAX_CONTRACTS,
			COMMISSION_PER_CONTRACT,
			SLIPPAGE_TICKS,
			MAX_OPEN_POSITIONS,
			MAX_AGGREGATE_CONTRACTS,
			MAX_AGGREGATE_MINI_UNITS,
			true,
			0.0,
			FUNDED_PROFILE,
			preset,
			0,
			true
		);
	}

	private static void runIsolatedStrategySweeps(List<RunSummary> runs) throws Exception {
		List<String[]> targets = new ArrayList<String[]>();
		targets.add(new String[] {"M2K", "OMOM"});
		targets.add(new String[] {"MES", "OMOM"});
		targets.add(new String[] {"MGC", "OMOM"});
		targets.add(new String[] {"MNQ", "OMOM"});
		targets.add(new String[] {"MYM", "OMOM"});
		targets.add(new String[] {"NQ", "OMOM"});
		targets.add(new String[] {"MNQ", "SWEEP"});
		targets.add(new String[] {"NQ", "SWEEP"});
		targets.add(new String[] {"MGC", "SWEEP"});
		targets.add(new String[] {"ES", "SWEEP"});
		targets.add(new String[] {"MYM", "SWEEP"});
		targets.add(new String[] {"MNQ", "FVG"});
		targets.add(new String[] {"NQ", "FVG"});
		targets.add(new String[] {"MGC", "PDB"});
		targets.add(new String[] {"MNQ", "PDB"});
		targets.add(new String[] {"NQ", "PDB"});
		targets.add(new String[] {"M2K", "VPB"});
		targets.add(new String[] {"MCL", "AFT"});
		targets.add(new String[] {"MES", "AFT"});
		targets.add(new String[] {"MNQ", "AFT"});
		targets.add(new String[] {"NQ", "LORB"});
		targets.add(new String[] {"MGC", "MSCALP"});
		targets.add(new String[] {"ES", "VWAP"});
		targets.add(new String[] {"MNQ", "VWAP"});
		targets.add(new String[] {"NQ", "VWAP"});
		targets.add(new String[] {"MGC", "VWAP"});
		targets.add(new String[] {"MNQ", "VRCL"});
		targets.add(new String[] {"NQ", "VRCL"});
		targets.add(new String[] {"MNQ", "KREV"});
		targets.add(new String[] {"NQ", "KREV"});
		targets.add(new String[] {"MGC", "KREV"});
		targets.add(new String[] {"ES", "ORB"});
		targets.add(new String[] {"MCL", "ORB"});
		targets.add(new String[] {"MGC", "ORB"});
		targets.add(new String[] {"MNQ", "ORB"});
		targets.add(new String[] {"MYM", "ORB"});
		targets.add(new String[] {"NQ", "ORB"});
		targets.add(new String[] {"M2K", "CMOM"});
		targets.add(new String[] {"MCL", "CMOM"});
		targets.add(new String[] {"MES", "CMOM"});
		targets.add(new String[] {"MGC", "CMOM"});
		targets.add(new String[] {"MYM", "CMOM"});
		targets.add(new String[] {"NQ", "MIM"});
		targets.add(new String[] {"MCL", "MIM"});

		for (String[] target : targets) {
			final String symbol = target[0];
			final String code = target[1];
			if (!isEnabledFor(symbol, code)) {
				continue;
			}
			runs.add(runVariant(
				"isolated_" + symbol.toLowerCase(Locale.US) + "_" + code.toLowerCase(Locale.US) + "_current",
				"Only " + symbol + " " + code + " enabled; current window.",
				symbol,
				new SettingsModifier() {
					@Override
					public void apply(String currentSymbol, FuturesManager.FuturesStrategySettings settings) {
						disableAllStrategies(settings);
						if (symbol.equals(currentSymbol)) {
							enableStrategy(settings, code);
						}
					}
				}
			));
			if (hasRelaxedWindowVariant(code)) {
				runs.add(runVariant(
					"isolated_" + symbol.toLowerCase(Locale.US) + "_" + code.toLowerCase(Locale.US) + "_relaxed",
					"Only " + symbol + " " + code + " enabled; relaxed/natural window.",
					symbol,
					new SettingsModifier() {
						@Override
						public void apply(String currentSymbol, FuturesManager.FuturesStrategySettings settings) {
							disableAllStrategies(settings);
							if (symbol.equals(currentSymbol)) {
								enableStrategy(settings, code);
								relaxWindowForCode(settings, code);
							}
						}
					},
					researchCodesForCode(code)
				));
			}
		}
	}

	private static boolean isEnabledFor(String symbol, String code) {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, FuturesManager.strategyPresetSlot(BASE_PRESET));
		return enabledCodes(settings).contains(code);
	}

	private static boolean hasRelaxedWindowVariant(String code) {
		return "OMOM".equals(code)
			|| "SWEEP".equals(code)
			|| "FVG".equals(code)
			|| "PDB".equals(code)
			|| "VWAP".equals(code)
			|| "VRCL".equals(code)
			|| "VPB".equals(code)
			|| "AFT".equals(code)
			|| "LORB".equals(code)
			|| "KELT".equals(code)
			|| "KREV".equals(code)
			|| "MRVWAP".equals(code)
			|| "MSCALP".equals(code);
	}

	private static void relaxWindowForCode(FuturesManager.FuturesStrategySettings settings, String code) {
		if ("OMOM".equals(code)) {
			relaxOpeningMomentumToNaturalOpen(settings);
		} else if ("SWEEP".equals(code)) {
			relaxSweepWindow(settings);
		} else if ("FVG".equals(code)) {
			relaxFvgWindow(settings);
		} else if ("PDB".equals(code)) {
			relaxPriorDayBreakoutWindow(settings);
		} else if ("VWAP".equals(code)) {
			relaxVwapWindow(settings);
		} else if ("VRCL".equals(code)) {
			settings.vwapReclaimBucketMinutes = Math.max(5, settings.vwapReclaimBucketMinutes);
		} else if ("VPB".equals(code)) {
			relaxValueAreaWindow(settings);
		} else if ("AFT".equals(code)) {
			relaxAfternoonWindow(settings);
		} else if ("LORB".equals(code)) {
			settings.lateOrbContinuationStartMinute = 660;
			settings.lateOrbContinuationEndMinute = 720;
		} else if ("KELT".equals(code) || "KREV".equals(code) || "MRVWAP".equals(code)) {
			// Hard-coded windows for these are relaxed through the JVM research property.
		} else if ("MSCALP".equals(code)) {
			settings.microScalpStartMinute = 570;
			settings.microScalpEndMinute = 920;
			settings.microScalpLongStartMinute = 0;
			settings.microScalpLongEndMinute = 0;
			settings.microScalpShortStartMinute = 0;
			settings.microScalpShortEndMinute = 0;
			settings.microScalpSkipStartMinute = 0;
			settings.microScalpSkipEndMinute = 0;
			settings.microScalpSkipDowMask = 0;
		}
	}

	private static String researchCodesForCode(String code) {
		if ("SWEEP".equals(code) || "SWEEP2".equals(code)) {
			return "SWEEP";
		}
		if ("VWAP".equals(code) || "VRCL".equals(code) || "KELT".equals(code) || "KREV".equals(code) || "MRVWAP".equals(code)) {
			return code;
		}
		return "";
	}

	private static String hardWindowCandidateCodes() {
		return "VWAP,VRCL,KELT,KREV,MRVWAP,SWEEP";
	}

	private static void relaxUnnecessaryWindowCandidates(FuturesManager.FuturesStrategySettings settings) {
		relaxSweepWindow(settings);
		relaxFvgWindow(settings);
		relaxPriorDayBreakoutWindow(settings);
		relaxVwapWindow(settings);
		relaxValueAreaWindow(settings);
		settings.microScalpStartMinute = 570;
		settings.microScalpEndMinute = 920;
		settings.microScalpLongStartMinute = 0;
		settings.microScalpLongEndMinute = 0;
		settings.microScalpShortStartMinute = 0;
		settings.microScalpShortEndMinute = 0;
		settings.microScalpSkipStartMinute = 0;
		settings.microScalpSkipEndMinute = 0;
		settings.microScalpSkipDowMask = 0;
		settings.microShadowStartMinute = 570;
		settings.microShadowEndMinute = 920;
		settings.microEchoStartMinute = 570;
		settings.microEchoEndMinute = 920;
		settings.winnerFollowThroughStartMinute = 570;
		settings.winnerFollowThroughEndMinute = 920;
	}

	private static void relaxPatternLevelWindows(FuturesManager.FuturesStrategySettings settings) {
		relaxFvgWindow(settings);
		relaxPriorDayBreakoutWindow(settings);
		relaxVwapWindow(settings);
		relaxValueAreaWindow(settings);
		settings.microScalpStartMinute = 570;
		settings.microScalpEndMinute = 920;
		settings.microScalpLongStartMinute = 0;
		settings.microScalpLongEndMinute = 0;
		settings.microScalpShortStartMinute = 0;
		settings.microScalpShortEndMinute = 0;
		settings.microScalpSkipStartMinute = 0;
		settings.microScalpSkipEndMinute = 0;
		settings.microScalpSkipDowMask = 0;
		settings.microShadowStartMinute = 570;
		settings.microShadowEndMinute = 920;
		settings.microEchoStartMinute = 570;
		settings.microEchoEndMinute = 920;
		settings.winnerFollowThroughStartMinute = 570;
		settings.winnerFollowThroughEndMinute = 920;
		settings.trendLadderStartMinute = 570;
		settings.trendLadderEndMinute = 920;
		settings.rangeCompressionStartMinute = 570;
		settings.rangeCompressionEndMinute = 920;
		settings.rangeCompressionSkipStartMinute = 0;
		settings.rangeCompressionSkipEndMinute = 0;
		settings.mymIndexConfirmationStartMinute = 570;
		settings.mymIndexConfirmationEndMinute = 920;
		settings.mclTrendStartMinute = 570;
		settings.mclTrendEndMinute = 920;
	}

	private static void relaxSweepWindow(FuturesManager.FuturesStrategySettings settings) {
		settings.sweepShortSkipStartMinute = 0;
		settings.sweepShortSkipEndMinute = 0;
	}

	private static void relaxVwapWindow(FuturesManager.FuturesStrategySettings settings) {
		settings.vwapStartMinute = 0;
		settings.vwapEndMinute = 0;
		settings.vwapSkipStartMinute = 0;
		settings.vwapSkipEndMinute = 0;
		settings.vwapShortSkipDowMask = 0;
	}

	private static void relaxOpeningMomentumToNaturalOpen(FuturesManager.FuturesStrategySettings settings) {
		removeOmomExtraWindows(settings);
		settings.openingMomentumLongStartMinute = 570;
		settings.openingMomentumLongEndMinute = 660;
		settings.openingMomentumShortStartMinute = 590;
		settings.openingMomentumShortEndMinute = 660;
		settings.openingMomentumLongSkipStartMinute = 0;
		settings.openingMomentumLongSkipEndMinute = 0;
		settings.openingMomentumShortSkipStartMinute = 0;
		settings.openingMomentumShortSkipEndMinute = 0;
		settings.openingMomentumLongSkipDowMask = 0;
		settings.openingMomentumShortSkipDowMask = 0;
	}

	private static void removeOmomExtraWindows(FuturesManager.FuturesStrategySettings settings) {
		settings.openingMomentumLongExtraWindows = "";
		settings.openingMomentumShortExtraWindows = "";
		settings.openingMomentumShortAltEnabled = false;
		settings.openingMomentumShortAltStartMinute = 0;
		settings.openingMomentumShortAltEndMinute = 0;
		settings.openingMomentumShortAltSkipDowMask = 0;
	}

	private static void relaxFvgWindow(FuturesManager.FuturesStrategySettings settings) {
		settings.fvgStartMinute = 570;
		settings.fvgEndMinute = 930;
		settings.fvgSkipStartMinute = 0;
		settings.fvgSkipEndMinute = 0;
		settings.fvgLongSkipDowMask = 0;
		settings.fvgShortSkipDowMask = 0;
		settings.fvgLongDowWindowSkipMask = 0;
		settings.fvgLongDowWindowSkipStartMinute = 0;
		settings.fvgLongDowWindowSkipEndMinute = 0;
		settings.fvgShortDowWindowSkipMask = 0;
		settings.fvgShortDowWindowSkipStartMinute = 0;
		settings.fvgShortDowWindowSkipEndMinute = 0;
	}

	private static void relaxPriorDayBreakoutWindow(FuturesManager.FuturesStrategySettings settings) {
		settings.priorDayBreakoutStartMinute = 570;
		settings.priorDayBreakoutEndMinute = 930;
		settings.priorDayBreakoutLongSkipStartMinute = 0;
		settings.priorDayBreakoutLongSkipEndMinute = 0;
		settings.priorDayBreakoutShortSkipStartMinute = 0;
		settings.priorDayBreakoutShortSkipEndMinute = 0;
		settings.priorDayBreakoutShortSecondSkipStartMinute = 0;
		settings.priorDayBreakoutShortSecondSkipEndMinute = 0;
		settings.priorDayBreakoutShortThirdSkipStartMinute = 0;
		settings.priorDayBreakoutShortThirdSkipEndMinute = 0;
	}

	private static void relaxValueAreaWindow(FuturesManager.FuturesStrategySettings settings) {
		settings.valueAreaStartMinute = 570;
		settings.valueAreaEndMinute = 920;
	}

	private static void relaxAfternoonWindow(FuturesManager.FuturesStrategySettings settings) {
		settings.afternoonStartMinute = 570;
		settings.afternoonEndMinute = 920;
		settings.afternoonLongStartMinute = 0;
		settings.afternoonLongEndMinute = 0;
		settings.afternoonShortStartMinute = 0;
		settings.afternoonShortEndMinute = 0;
		settings.afternoonSkipStartMinute = 0;
		settings.afternoonSkipEndMinute = 0;
		settings.afternoonShortSkipDowMask = 0;
	}

	private static void disableAllStrategies(FuturesManager.FuturesStrategySettings settings) {
		settings.orb.enabled = false;
		settings.enableOrbRetest = false;
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

	private static void enableStrategy(FuturesManager.FuturesStrategySettings settings, String code) {
		if ("ORB".equals(code)) {
			settings.orb.enabled = true;
		} else if ("ORB2".equals(code)) {
			settings.orb.enabled = true;
			settings.enableOrbRetest = true;
		} else if ("LORB".equals(code)) {
			settings.lateOrbContinuation.enabled = true;
		} else if ("OMOM".equals(code)) {
			settings.openingMomentum.enabled = true;
		} else if ("SWEEP".equals(code) || "SWEEP2".equals(code)) {
			settings.sweep.enabled = true;
		} else if ("PDB".equals(code)) {
			settings.priorDayBreakout.enabled = true;
		} else if ("VWAP".equals(code)) {
			settings.vwapPullback.enabled = true;
		} else if ("VRCL".equals(code)) {
			settings.vwapReclaim.enabled = true;
		} else if ("MRVWAP".equals(code)) {
			settings.vwapMeanReversion.enabled = true;
		} else if ("FVG".equals(code)) {
			settings.fvg.enabled = true;
		} else if ("CMOM".equals(code)) {
			settings.closeMomentum.enabled = true;
		} else if ("AFT".equals(code)) {
			settings.afternoonContinuation.enabled = true;
		} else if ("MIM".equals(code) || "IPB".equals(code)) {
			settings.marketIntradayMomentum.enabled = true;
		} else if ("KELT".equals(code)) {
			settings.keltnerScalp.enabled = true;
		} else if ("KREV".equals(code)) {
			settings.keltnerReversion.enabled = true;
		} else if ("MSCALP".equals(code)) {
			settings.microScalp.enabled = true;
		} else if ("SHDW".equals(code)) {
			settings.microShadow.enabled = true;
		} else if ("ECHO".equals(code)) {
			settings.microEcho.enabled = true;
		} else if ("WFT".equals(code)) {
			settings.winnerFollowThrough.enabled = true;
		} else if ("TLAD".equals(code)) {
			settings.trendLadder.enabled = true;
		} else if ("RCB".equals(code)) {
			settings.rangeCompressionBreakout.enabled = true;
		} else if ("VPB".equals(code)) {
			settings.valueAreaReclaim.enabled = true;
		} else if ("EIA".equals(code)) {
			settings.mclEiaContinuation.enabled = true;
		} else if ("COPEN".equals(code)) {
			settings.mclCrudeSessionOpen.enabled = true;
		} else if ("IDXCONF".equals(code)) {
			settings.mymIndexConfirmation.enabled = true;
		} else if ("MYMORB2".equals(code)) {
			settings.mymOrbRetest.enabled = true;
		} else if ("MYMBR".equals(code)) {
			settings.mymBreadthConfirmation.enabled = true;
		} else if ("MCLTC".equals(code)) {
			settings.mclTrendContinuation.enabled = true;
		}
	}

	private static List<String> enabledCodes(FuturesManager.FuturesStrategySettings settings) {
		List<String> codes = new ArrayList<String>();
		if (settings.orb.enabled) codes.add("ORB");
		if (settings.orb.enabled && settings.enableOrbRetest) codes.add("ORB2");
		if (settings.lateOrbContinuation.enabled) codes.add("LORB");
		if (settings.openingMomentum.enabled) codes.add("OMOM");
		if (settings.sweep.enabled) codes.add("SWEEP");
		if (settings.priorDayBreakout.enabled) codes.add("PDB");
		if (settings.vwapPullback.enabled) codes.add("VWAP");
		if (settings.vwapReclaim.enabled) codes.add("VRCL");
		if (settings.vwapMeanReversion.enabled) codes.add("MRVWAP");
		if (settings.fvg.enabled) codes.add("FVG");
		if (settings.closeMomentum.enabled) codes.add("CMOM");
		if (settings.afternoonContinuation.enabled) codes.add("AFT");
		if (settings.marketIntradayMomentum.enabled) {
			codes.add("MIM");
			codes.add("IPB");
		}
		if (settings.keltnerScalp.enabled) codes.add("KELT");
		if (settings.keltnerReversion.enabled) codes.add("KREV");
		if (settings.microScalp.enabled) codes.add("MSCALP");
		if (settings.microShadow.enabled) codes.add("SHDW");
		if (settings.microEcho.enabled) codes.add("ECHO");
		if (settings.winnerFollowThrough.enabled) codes.add("WFT");
		if (settings.trendLadder.enabled) codes.add("TLAD");
		if (settings.rangeCompressionBreakout.enabled) codes.add("RCB");
		if (settings.valueAreaReclaim.enabled) codes.add("VPB");
		if (settings.mclEiaContinuation.enabled) codes.add("EIA");
		if (settings.mclCrudeSessionOpen.enabled) codes.add("COPEN");
		if (settings.mymIndexConfirmation.enabled) codes.add("IDXCONF");
		if (settings.mymOrbRetest.enabled) codes.add("MYMORB2");
		if (settings.mymBreadthConfirmation.enabled) codes.add("MYMBR");
		if (settings.mclTrendContinuation.enabled) codes.add("MCLTC");
		return codes;
	}

	private static String windowConcerns(FuturesManager.FuturesStrategySettings settings) {
		List<String> concerns = new ArrayList<String>();
		if (settings.orb.enabled) concerns.add("ORB hard `09:45-11:00`; shorts after `09:50`; skips L/S " + window(settings.orbLongSkipStartMinute, settings.orbLongSkipEndMinute) + "/" + window(settings.orbShortSkipStartMinute, settings.orbShortSkipEndMinute));
		if (settings.lateOrbContinuation.enabled) concerns.add("LORB `" + hhmm(settings.lateOrbContinuationStartMinute) + "-" + hhmm(settings.lateOrbContinuationEndMinute) + "`");
		if (settings.openingMomentum.enabled) concerns.add("OMOM L `" + hhmm(settings.openingMomentumLongStartMinute) + "-" + hhmm(settings.openingMomentumLongEndMinute) + "`, S `" + hhmm(settings.openingMomentumShortStartMinute) + "-" + hhmm(settings.openingMomentumShortEndMinute) + "`, extra windows L/S " + countExtraWindows(settings.openingMomentumLongExtraWindows) + "/" + countExtraWindows(settings.openingMomentumShortExtraWindows));
		if (settings.sweep.enabled) concerns.add("SWEEP hard `13:00-14:30`; short skip " + window(settings.sweepShortSkipStartMinute, settings.sweepShortSkipEndMinute));
		if (settings.priorDayBreakout.enabled) concerns.add("PDB `" + hhmm(settings.priorDayBreakoutStartMinute) + "-" + hhmm(settings.priorDayBreakoutEndMinute) + "` plus short skips");
		if (settings.vwapPullback.enabled) concerns.add("VWAP native `09:45-15:30`, setting window " + optionalWindow(settings.vwapStartMinute, settings.vwapEndMinute));
		if (settings.vwapReclaim.enabled) concerns.add("VRCL hard-coded `10:00-15:10`");
		if (settings.fvg.enabled) concerns.add("FVG `" + hhmm(settings.fvgStartMinute) + "-" + hhmm(settings.fvgEndMinute) + "`, skip " + window(settings.fvgSkipStartMinute, settings.fvgSkipEndMinute));
		if (settings.closeMomentum.enabled) concerns.add("CMOM L `" + hhmm(settings.closeMomentumLongStartMinute) + "-" + hhmm(settings.closeMomentumLongEndMinute) + "`, S `" + hhmm(settings.closeMomentumShortStartMinute) + "-" + hhmm(settings.closeMomentumShortEndMinute) + "`");
		if (settings.afternoonContinuation.enabled) concerns.add("AFT `" + hhmm(settings.afternoonStartMinute) + "-" + hhmm(settings.afternoonEndMinute) + "`, L/S " + optionalWindow(settings.afternoonLongStartMinute, settings.afternoonLongEndMinute) + "/" + optionalWindow(settings.afternoonShortStartMinute, settings.afternoonShortEndMinute) + ", skip " + window(settings.afternoonSkipStartMinute, settings.afternoonSkipEndMinute));
		if (settings.marketIntradayMomentum.enabled) concerns.add("MIM/IPB impulse `" + hhmm(settings.marketImpulsePullbackStartMinute) + "-" + hhmm(settings.marketImpulsePullbackEndMinute) + "`, late MIM hard `15:25-15:35`");
		if (settings.microScalp.enabled) concerns.add("MSCALP `" + hhmm(settings.microScalpStartMinute) + "-" + hhmm(settings.microScalpEndMinute) + "`, side windows " + optionalWindow(settings.microScalpLongStartMinute, settings.microScalpLongEndMinute) + "/" + optionalWindow(settings.microScalpShortStartMinute, settings.microScalpShortEndMinute));
		if (settings.microShadow.enabled) concerns.add("SHDW `" + hhmm(settings.microShadowStartMinute) + "-" + hhmm(settings.microShadowEndMinute) + "`");
		if (settings.microEcho.enabled) concerns.add("ECHO `" + hhmm(settings.microEchoStartMinute) + "-" + hhmm(settings.microEchoEndMinute) + "`");
		if (settings.winnerFollowThrough.enabled) concerns.add("WFT `" + hhmm(settings.winnerFollowThroughStartMinute) + "-" + hhmm(settings.winnerFollowThroughEndMinute) + "`");
		if (settings.valueAreaReclaim.enabled) concerns.add("VPB `" + hhmm(settings.valueAreaStartMinute) + "-" + hhmm(settings.valueAreaEndMinute) + "`");
		if (settings.keltnerReversion.enabled) concerns.add("KREV hard-coded keltner session window");
		if (settings.keltnerScalp.enabled) concerns.add("KELT hard-coded keltner session window");
		if (concerns.isEmpty()) return "";
		return join(concerns, "<br>");
	}

	private static RunSummary loadRunSummary(int id) throws Exception {
		RunSummary summary = new RunSummary();
		summary.id = id;
		String sql = "SELECT portfolioBacktestID, symbols, totalProfit, winRate, numTrades, profitFactor, maxDrawdownPct, "
			+ "maxIntradayLoss, dailyLossBreaches, trailingDrawdownBreaches, maeBreaches, ruleViolation, ruleMessage "
			+ "FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, id);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					summary.symbols = rs.getString("symbols");
					summary.pnl = rs.getDouble("totalProfit");
					summary.winRate = rs.getDouble("winRate");
					summary.trades = rs.getInt("numTrades");
					summary.profitFactor = rs.getDouble("profitFactor");
					summary.maxDrawdownPct = rs.getDouble("maxDrawdownPct");
					summary.maxIntradayLoss = rs.getDouble("maxIntradayLoss");
					summary.dailyLossBreaches = rs.getInt("dailyLossBreaches");
					summary.trailingDrawdownBreaches = rs.getInt("trailingDrawdownBreaches");
					summary.maeBreaches = rs.getInt("maeBreaches");
					summary.ruleViolation = rs.getInt("ruleViolation");
					summary.ruleMessage = rs.getString("ruleMessage");
				}
			}
		}
		summary.avgPnl = summary.trades == 0 ? 0.0 : summary.pnl / summary.trades;
		return summary;
	}

	private static void labelRun(int id, String scenario) throws Exception {
		if (id <= 0) {
			return;
		}
		try (Connection conn = DatabaseManager.getConnection();
			 Statement create = conn.createStatement()) {
			create.execute("CREATE TABLE IF NOT EXISTS ResearchRunLabels (portfolioBacktestID INTEGER PRIMARY KEY, runner TEXT, scenarioName TEXT, createdAt TEXT)");
		}
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO ResearchRunLabels (portfolioBacktestID, runner, scenarioName, createdAt) VALUES (?, ?, ?, datetime('now'))")) {
			stmt.setInt(1, id);
			stmt.setString(2, RUNNER);
			stmt.setString(3, scenario);
			stmt.executeUpdate();
		}
	}

	private static void appendRunTable(StringBuilder report, List<RunSummary> runs) {
		report.append("| Scenario | ID | Symbols | Trades | PnL | Avg/trade | Win % | PF | Max DD % | Worst intraday | Rule breaches | Notes |\n");
		report.append("|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|---|\n");
		for (RunSummary run : runs) {
			report.append("| `").append(run.scenario).append("` | ").append(run.id)
				.append(" | `").append(run.symbols).append("`")
				.append(" | ").append(run.trades)
				.append(" | ").append(money(run.pnl))
				.append(" | ").append(money(run.avgPnl))
				.append(" | ").append(round(run.winRate))
				.append(" | ").append(round(run.profitFactor))
				.append(" | ").append(round(run.maxDrawdownPct))
				.append(" | ").append(money(run.maxIntradayLoss))
				.append(" | ").append(run.dailyLossBreaches).append("/").append(run.trailingDrawdownBreaches).append("/").append(run.maeBreaches)
				.append(" | ").append(run.description == null ? "" : run.description.replace("|", "/"))
				.append(" |\n");
		}
		report.append("\n");
	}

	private static void appendRunDeltas(StringBuilder report, List<RunSummary> runs) {
		if (runs.isEmpty()) {
			return;
		}
		RunSummary baseline = runs.get(0);
		report.append("### Portfolio Deltas Vs Baseline\n\n");
		report.append("| Scenario | Trade Delta | PnL Delta | Avg/Trade Delta | Win Delta | PF Delta |\n");
		report.append("|---|---:|---:|---:|---:|---:|\n");
		for (int index = 1; index < runs.size(); index++) {
			RunSummary run = runs.get(index);
			report.append("| `").append(run.scenario).append("`")
				.append(" | ").append(run.trades - baseline.trades)
				.append(" | ").append(money(run.pnl - baseline.pnl))
				.append(" | ").append(money(run.avgPnl - baseline.avgPnl))
				.append(" | ").append(round(run.winRate - baseline.winRate))
				.append(" | ").append(round(run.profitFactor - baseline.profitFactor))
				.append(" |\n");
		}
		report.append("\n");
	}

	private static void appendBreakdownSection(StringBuilder report, int id, String label) throws Exception {
		report.append("### ").append(label).append(" Breakdowns\n\n");
		report.append("#### By Symbol And Strategy\n\n");
		appendMetricsTable(report, groupMetrics(id, "symbol || '/' || strategyCode", null, 60));
		report.append("#### By Time Bucket\n\n");
		appendMetricsTable(report, groupMetrics(id, timeBucketExpression(), null, 20));
	}

	private static void appendCompactComparison(StringBuilder report, RunSummary run) throws Exception {
		report.append("### ").append(run.scenario).append(" Top Breakdown\n\n");
		appendMetricsTable(report, groupMetrics(run.id, "symbol || '/' || strategyCode", null, 25));
		report.append("Time buckets:\n\n");
		appendMetricsTable(report, groupMetrics(run.id, timeBucketExpression(), null, 10));
	}

	private static void appendMetricsTable(StringBuilder report, List<GroupMetrics> rows) {
		report.append("| Group | Trades | PnL | Avg | Win % | PF | Avg Win | Avg Loss | Max DD $ | Avg Hold | Avg MFE | Avg MAE | Target % | Stop % |\n");
		report.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
		for (GroupMetrics row : rows) {
			report.append("| `").append(row.key).append("`")
				.append(" | ").append(row.trades)
				.append(" | ").append(money(row.pnl))
				.append(" | ").append(money(row.avgPnl))
				.append(" | ").append(round(row.winRate))
				.append(" | ").append(round(row.profitFactor))
				.append(" | ").append(money(row.avgWin))
				.append(" | ").append(money(row.avgLoss))
				.append(" | ").append(money(row.maxDrawdown))
				.append(" | ").append(round(row.avgHoldMinutes))
				.append(" | ").append(money(row.avgMfe))
				.append(" | ").append(money(row.avgMae))
				.append(" | ").append(round(row.targetRate))
				.append(" | ").append(round(row.stopRate))
				.append(" |\n");
		}
		report.append("\n");
	}

	private static List<GroupMetrics> groupMetrics(int backtestId, String groupExpression, String where, int limit) throws Exception {
		Map<String, GroupMetrics> rows = new LinkedHashMap<String, GroupMetrics>();
		String sql = "SELECT " + groupExpression + " AS grp, portfolioTradeID, pnl, mfe, mae, exitReason, openedAt, closedAt "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? "
			+ (where == null || where.trim().isEmpty() ? "" : " AND " + where)
			+ " ORDER BY grp, openedAt, portfolioTradeID";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setInt(1, backtestId);
			try (ResultSet rs = stmt.executeQuery()) {
				Map<String, List<Double>> pnlByGroup = new HashMap<String, List<Double>>();
				Map<String, double[]> sums = new HashMap<String, double[]>();
				while (rs.next()) {
					String key = rs.getString("grp");
					GroupMetrics metrics = rows.get(key);
					if (metrics == null) {
						metrics = new GroupMetrics();
						metrics.key = key;
						rows.put(key, metrics);
						pnlByGroup.put(key, new ArrayList<Double>());
						sums.put(key, new double[9]);
					}
					double pnl = rs.getDouble("pnl");
					metrics.trades++;
					metrics.pnl += pnl;
					metrics.avgMfe += rs.getDouble("mfe");
					metrics.avgMae += rs.getDouble("mae");
					if (pnl > 0.0) {
						metrics.avgWin += pnl;
						sums.get(key)[0] += 1.0;
					} else if (pnl < 0.0) {
						metrics.avgLoss += pnl;
						sums.get(key)[1] += 1.0;
					}
					if ("Target reached".equals(rs.getString("exitReason"))) {
						sums.get(key)[2] += 1.0;
					}
					String exitReason = rs.getString("exitReason") == null ? "" : rs.getString("exitReason");
					if (exitReason.toLowerCase(Locale.US).contains("stop")) {
						sums.get(key)[3] += 1.0;
					}
					double hold = minutesBetween(rs.getString("openedAt"), rs.getString("closedAt"));
					metrics.avgHoldMinutes += hold;
					pnlByGroup.get(key).add(Double.valueOf(pnl));
				}
				for (Map.Entry<String, GroupMetrics> entry : rows.entrySet()) {
					GroupMetrics metrics = entry.getValue();
					double[] groupSums = sums.get(entry.getKey());
					metrics.avgPnl = metrics.trades == 0 ? 0.0 : metrics.pnl / metrics.trades;
					metrics.winRate = metrics.trades == 0 ? 0.0 : (groupSums[0] * 100.0) / metrics.trades;
					metrics.avgWin = groupSums[0] == 0.0 ? 0.0 : metrics.avgWin / groupSums[0];
					metrics.avgLoss = groupSums[1] == 0.0 ? 0.0 : metrics.avgLoss / groupSums[1];
					metrics.profitFactor = grossProfit(pnlByGroup.get(entry.getKey())) / Math.max(0.01, grossLoss(pnlByGroup.get(entry.getKey())));
					metrics.avgMfe = metrics.trades == 0 ? 0.0 : metrics.avgMfe / metrics.trades;
					metrics.avgMae = metrics.trades == 0 ? 0.0 : metrics.avgMae / metrics.trades;
					metrics.avgHoldMinutes = metrics.trades == 0 ? 0.0 : metrics.avgHoldMinutes / metrics.trades;
					metrics.targetRate = metrics.trades == 0 ? 0.0 : (groupSums[2] * 100.0) / metrics.trades;
					metrics.stopRate = metrics.trades == 0 ? 0.0 : (groupSums[3] * 100.0) / metrics.trades;
					metrics.maxDrawdown = maxDrawdown(pnlByGroup.get(entry.getKey()));
				}
			}
		}
		List<GroupMetrics> sorted = new ArrayList<GroupMetrics>(rows.values());
		Collections.sort(sorted, new Comparator<GroupMetrics>() {
			@Override
			public int compare(GroupMetrics first, GroupMetrics second) {
				return Double.compare(second.pnl, first.pnl);
			}
		});
		if (limit > 0 && sorted.size() > limit) {
			return new ArrayList<GroupMetrics>(sorted.subList(0, limit));
		}
		return sorted;
	}

	private static void appendIsolatedVerdicts(StringBuilder report, List<RunSummary> runs) {
		Map<String, RunSummary> byScenario = new HashMap<String, RunSummary>();
		for (RunSummary run : runs) {
			byScenario.put(run.scenario, run);
		}
		report.append("### Isolated Relaxation Verdicts\n\n");
		report.append("| Pair | Current | Relaxed | Read |\n");
		report.append("|---|---:|---:|---|\n");
		for (RunSummary current : runs) {
			if (!current.scenario.endsWith("_current")) {
				continue;
			}
			String relaxedName = current.scenario.substring(0, current.scenario.length() - "_current".length()) + "_relaxed";
			RunSummary relaxed = byScenario.get(relaxedName);
			if (relaxed == null) {
				continue;
			}
			String read;
			double pnlDelta = relaxed.pnl - current.pnl;
			int tradeDelta = relaxed.trades - current.trades;
			if (relaxed.trades < Math.max(4, current.trades / 2) || relaxed.pnl < current.pnl * 0.60) {
				read = "Hard/narrow window is carrying a lot of edge or filtering major damage.";
			} else if (pnlDelta >= 0.0 && relaxed.winRate >= current.winRate - 5.0) {
				read = "Window can likely be softened/broadened without immediate damage.";
			} else if (tradeDelta > 0 && relaxed.avgPnl < current.avgPnl * 0.60) {
				read = "Broader window adds lower-quality trades; needs quality scoring before loosening.";
			} else {
				read = "Mixed; do not change defaults without a second pass.";
			}
			report.append("| `").append(current.scenario.replace("isolated_", "").replace("_current", "")).append("`")
				.append(" | ").append(money(current.pnl)).append(" / ").append(current.trades).append("t / ").append(round(current.winRate)).append("%")
				.append(" | ").append(money(relaxed.pnl)).append(" / ").append(relaxed.trades).append("t / ").append(round(relaxed.winRate)).append("%")
				.append(" | ").append(read)
				.append(" |\n");
		}
		report.append("\n");
	}

	private static void appendRecommendation(StringBuilder report, List<RunSummary> portfolioRuns, List<RunSummary> isolatedRuns) {
		report.append("## Recommendation\n\n");
		RunSummary baseline = portfolioRuns.get(0);
		RunSummary bestPnl = baseline;
		RunSummary bestAvg = baseline;
		for (RunSummary run : portfolioRuns) {
			if (run.pnl > bestPnl.pnl && run.ruleViolation == 0) {
				bestPnl = run;
			}
			if (run.avgPnl > bestAvg.avgPnl && run.trades >= baseline.trades * 0.60 && run.ruleViolation == 0) {
				bestAvg = run;
			}
		}
		report.append("Best PnL variant in this checkpoint: `").append(bestPnl.scenario).append("` (").append(money(bestPnl.pnl)).append(", ").append(bestPnl.trades).append(" trades, ").append(round(bestPnl.winRate)).append("% win, PF ").append(round(bestPnl.profitFactor)).append(").\n\n");
		report.append("Best average-trade variant with meaningful trade count: `").append(bestAvg.scenario).append("` (").append(money(bestAvg.avgPnl)).append("/trade, ").append(bestAvg.trades).append(" trades).\n\n");
		report.append("Decision guardrail: this report is evidence for review only. No `94k`, `wip`, live strategy, DTM, or live_backend behavior was changed by the sweep.\n");
	}

	private static String timeBucketExpression() {
		String minute = "(CAST(substr(openedAt,12,2) AS INTEGER) * 60 + CAST(substr(openedAt,15,2) AS INTEGER))";
		return "CASE "
			+ "WHEN " + minute + " >= 570 AND " + minute + " < 600 THEN '09:30-10:00' "
			+ "WHEN " + minute + " >= 600 AND " + minute + " < 660 THEN '10:00-11:00' "
			+ "WHEN " + minute + " >= 660 AND " + minute + " < 780 THEN '11:00-13:00' "
			+ "WHEN " + minute + " >= 780 AND " + minute + " < 870 THEN '13:00-14:30' "
			+ "WHEN " + minute + " >= 870 AND " + minute + " <= 945 THEN '14:30-15:45' "
			+ "ELSE 'other' END";
	}

	private static double grossProfit(List<Double> pnls) {
		double value = 0.0;
		for (Double pnl : pnls) {
			if (pnl.doubleValue() > 0.0) {
				value += pnl.doubleValue();
			}
		}
		return value;
	}

	private static double grossLoss(List<Double> pnls) {
		double value = 0.0;
		for (Double pnl : pnls) {
			if (pnl.doubleValue() < 0.0) {
				value += Math.abs(pnl.doubleValue());
			}
		}
		return value;
	}

	private static double maxDrawdown(List<Double> pnls) {
		double equity = 0.0;
		double peak = 0.0;
		double maxDrawdown = 0.0;
		for (Double pnl : pnls) {
			equity += pnl.doubleValue();
			peak = Math.max(peak, equity);
			maxDrawdown = Math.max(maxDrawdown, peak - equity);
		}
		return maxDrawdown;
	}

	private static double minutesBetween(String openedAt, String closedAt) {
		try {
			LocalDateTime opened = LocalDateTime.parse(openedAt.replace(" ", "T"));
			LocalDateTime closed = LocalDateTime.parse(closedAt.replace(" ", "T"));
			return java.time.Duration.between(opened, closed).toMinutes();
		} catch (Exception e) {
			return 0.0;
		}
	}

	private static String compactName(String value) {
		return value == null ? "scenario" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
	}

	private static String join(List<String> values, String delimiter) {
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < values.size(); index++) {
			if (index > 0) builder.append(delimiter);
			builder.append(values.get(index));
		}
		return builder.toString();
	}

	private static String hhmm(int minute) {
		int safe = Math.max(0, minute);
		return String.format("%02d:%02d", safe / 60, safe % 60);
	}

	private static String window(int start, int end) {
		if (start <= 0 || end <= 0 || end < start) return "none";
		return "`" + hhmm(start) + "-" + hhmm(end) + "`";
	}

	private static String optionalWindow(int start, int end) {
		if (start <= 0 || end <= 0 || end < start) return "native/default";
		return "`" + hhmm(start) + "-" + hhmm(end) + "`";
	}

	private static int countExtraWindows(String extraWindows) {
		if (extraWindows == null || extraWindows.trim().isEmpty()) {
			return 0;
		}
		String[] pieces = extraWindows.split(";");
		int count = 0;
		for (String piece : pieces) {
			if (piece != null && !piece.trim().isEmpty()) {
				count++;
			}
		}
		return count;
	}

	private static String money(double value) {
		return "$" + round(value);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
