package com.tradingbot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StrategyManager {

	private static final String ORB_CODE = "ORB";
	private static final String IFVG_CODE = "IFVG";
	private static final String VWAP_CODE = "VWAP";
	private static final String MRVWAP_CODE = "MRVWAP";
	private static final String GAPGO_CODE = "GAPGO";
	private static final String ORB_NAME = "Opening Range Breakout";
	private static final String IFVG_NAME = "Inverse Fair Value Gap";
	private static final String VWAP_NAME = "VWAP Trend Pullback";
	private static final String MRVWAP_NAME = "VWAP RSI Mean Reversion";
	private static final String GAPGO_NAME = "Gap and Go Continuation";
	private static final LocalTime ORB_ENTRY_CUTOFF = LocalTime.of(11, 0);
	private static final LocalTime ORB_OPENING_DRIVE_TIME = LocalTime.of(10, 5);
	private static final LocalTime IFVG_LONG_ENTRY_START = LocalTime.of(9, 45);
	private static final LocalTime IFVG_SHORT_ENTRY_START = LocalTime.of(9, 45);
	private static final LocalTime IFVG_NEUTRAL_SHORT_ENTRY_CUTOFF = LocalTime.of(13, 30);
	private static final LocalTime IFVG_ENTRY_CUTOFF = LocalTime.of(15, 30);
	private static final LocalTime VWAP_ENTRY_START = LocalTime.of(9, 45);
	private static final LocalTime VWAP_ENTRY_CUTOFF = LocalTime.of(15, 15);
	private static final LocalTime VWAP_OPEN_CHOP_START = LocalTime.of(10, 0);
	private static final LocalTime VWAP_OPEN_CHOP_END = LocalTime.of(11, 0);
	private static final LocalTime MRVWAP_ENTRY_START = LocalTime.of(10, 0);
	private static final LocalTime MRVWAP_ENTRY_CUTOFF = LocalTime.of(15, 15);
	private static final LocalTime MRVWAP_LATE_MORNING_CHOP_START = LocalTime.of(11, 0);
	private static final LocalTime MRVWAP_LATE_MORNING_CHOP_END = LocalTime.of(12, 0);
	private static final LocalTime GAPGO_ENTRY_CUTOFF = LocalTime.of(11, 30);
	private static final double MIN_BREAKOUT_BODY_RATIO = 0.35;
	private static final double MIN_BREAKOUT_CLOSE_POSITION = 0.6;
	private static final double MIN_RECLAIM_CLOSE_POSITION = 0.55;
	private static final double MAX_BREAKOUT_EXTENSION_RANGE_MULTIPLE = 1.0;
	private static final double MAX_RECLAIM_EXTENSION_GAP_MULTIPLE = 1.0;
	private static final double MAX_ORB_RETEST_RANGE_PENETRATION_RATIO = 0.5;
	private static final double MIN_ORB_BREAKOUT_VOLUME_RATIO = 1.0;
	private static final double MIN_ORB_DRIVE_VOLUME_RATIO = 0.8;
	private static final double MAX_ORB_DRIVE_EXTENSION_RANGE_MULTIPLE = 1.5;
	private static final double MIN_IFVG_RECLAIM_VOLUME_RATIO = 0.75;
	private static final double MIN_NEUTRAL_SHORT_RECLAIM_VOLUME_RATIO = 1.0;
	private static final double MIN_IFVG_IMPULSE_BODY_RATIO = 0.5;
	private static final double MIN_IFVG_IMPULSE_RANGE_RATIO = 1.15;
	private static final double MIN_VWAP_CONFIRM_VOLUME_RATIO = 0.9;
	private static final double MRVWAP_OVERSOLD_RSI = 32.0;
	private static final double MRVWAP_OVERBOUGHT_RSI = 68.0;
	private static final double MIN_MRVWAP_REVERSAL_CLOSE_POSITION = 0.55;
	private static final double MIN_GAPGO_BREAKOUT_VOLUME_RATIO = 1.0;
	private static final double MAX_GAPGO_EXTENSION_RANGE_MULTIPLE = 1.5;
	private static final int MAX_CONCURRENT_TRADES_PER_SYMBOL = configuredInt(
		"TRADING_BOT_MAX_CONCURRENT_TRADES_PER_SYMBOL",
		"tradingbot.maxConcurrentTradesPerSymbol",
		4,
		1,
		8
	);
	private static final int MIN_MINUTES_BETWEEN_PORTFOLIO_ENTRIES = configuredInt(
		"TRADING_BOT_MIN_MINUTES_BETWEEN_ENTRIES",
		"tradingbot.minMinutesBetweenEntries",
		1,
		0,
		15
	);
	private static final double MIN_DISTINCT_ENTRY_PRICE_PCT = configuredDouble(
		"TRADING_BOT_MIN_DISTINCT_ENTRY_PRICE_PCT",
		"tradingbot.minDistinctEntryPricePct",
		0.02,
		0.0,
		1.0
	);
	private static final int VOLATILITY_LOOKBACK_BARS = 20;
	private static final double MAX_RECENT_RANGE_PCT = configuredDouble(
		"TRADING_BOT_MAX_RECENT_RANGE_PCT",
		"tradingbot.maxRecentRangePct",
		0.15,
		0.03,
		0.5
	);
	public static class StrategyConfig {
		public int strategyId;
		public String strategyCode;
		public String strategyName;
		public String description;
		public String timeframe;
		public double riskPerTradePct;
		public int maxTradesPerDay;
		public boolean isEnabled;
		public String trendTimeframe;
		public String signalTimeframe;
		public double rewardToRiskRatio;
		public boolean requireTrendAlignment;
		public int orbWindowMinutes;
		public double breakoutBufferPct;
		public double minimumGapPct;
		public int reclaimWindowBars;
		public double entryBufferPct;
		public double stopBufferPct;

		public StrategyConfig copy() {
			StrategyConfig copy = new StrategyConfig();
			copy.strategyId = strategyId;
			copy.strategyCode = strategyCode;
			copy.strategyName = strategyName;
			copy.description = description;
			copy.timeframe = timeframe;
			copy.riskPerTradePct = riskPerTradePct;
			copy.maxTradesPerDay = maxTradesPerDay;
			copy.isEnabled = isEnabled;
			copy.trendTimeframe = trendTimeframe;
			copy.signalTimeframe = signalTimeframe;
			copy.rewardToRiskRatio = rewardToRiskRatio;
			copy.requireTrendAlignment = requireTrendAlignment;
			copy.orbWindowMinutes = orbWindowMinutes;
			copy.breakoutBufferPct = breakoutBufferPct;
			copy.minimumGapPct = minimumGapPct;
			copy.reclaimWindowBars = reclaimWindowBars;
			copy.entryBufferPct = entryBufferPct;
			copy.stopBufferPct = stopBufferPct;
			return copy;
		}
	}

	public static class StrategySettings {
		public StrategyConfig orb;
		public StrategyConfig ifvg;
		public StrategyConfig vwapPullback;
		public StrategyConfig vwapMeanReversion;
		public StrategyConfig gapGo;

		public boolean hasEnabledStrategies() {
			return (orb != null && orb.isEnabled)
				|| (ifvg != null && ifvg.isEnabled)
				|| (vwapPullback != null && vwapPullback.isEnabled)
				|| (vwapMeanReversion != null && vwapMeanReversion.isEnabled)
				|| (gapGo != null && gapGo.isEnabled);
		}
	}

	public static class TradeRecord {
		public String strategyCode;
		public String side;
		public double qty;
		public double entryPrice;
		public double exitPrice;
		public String openedAt;
		public String closedAt;
		public String tradeNotes;
		public double pnl;
		public LocalTime openedTime;
		public LocalTime closedTime;
	}

	public static class LiveSignalSnapshot {
		public String strategyCode;
		public String strategyName;
		public String side;
		public double entryPrice;
		public double stopPrice;
		public double targetPrice;
		public String openedAt;
		public String closedAt;
		public String tradeNotes;
		public LocalTime openedTime;
		public LocalTime closedTime;
		public double coordinationScore;
	}

	public static class StrategyBacktest {
		public LocalDate startDate;
		public LocalDate endDate;
		public double perTradeBuyingPower;
		public double takeProfit;
		public double lossLimit;
		public double endingCapital;
		public double totalProfit;
		public double returnPct;
		public double winRate;
		public double profitFactor;
		public double maxDrawdownPct;
		public String timeframeSummary;
		public List<TradeRecord> trades = new ArrayList<TradeRecord>();
	}

	private static class Signal {
		private String strategyCode;
		private String side;
		private double entryPrice;
		private double stopPrice;
		private double targetPrice;
		private double exitPrice;
		private String openedAt;
		private String closedAt;
		private String tradeNotes;
		private LocalTime openedTime;
		private LocalTime closedTime;
		private double failureExitPrice;
		private List<AlpacaManager.CachedBar> bars;
		private int entryIndex;
		private double coordinationScore;
	}

	private static class TradeExit {
		private double exitPrice;
		private String closedAt;
		private LocalTime closedTime;
		private String reason;
	}

	private static class TradeExcursion {
		private double maxFavorablePnl;
		private double maxAdversePnl;
	}

	private static class BacktestLedger {
		private double currentCapital;
		private double peakEquity;
		private double grossProfit;
		private double grossLoss;
		private double maxDrawdownPct;
		private int winningTrades;
		private List<TradeRecord> trades = new ArrayList<TradeRecord>();
	}

	private enum Bias {
		BULLISH,
		BEARISH,
		NEUTRAL
	}

	public static void initializeStrategyStore(Connection conn) throws SQLException {
		ensureColumnExists(conn, "Strategies", "strategyCode", "TEXT");
		ensureColumnExists(conn, "Strategies", "trendTimeframe", "TEXT");
		ensureColumnExists(conn, "Strategies", "signalTimeframe", "TEXT");
		ensureColumnExists(conn, "Strategies", "rewardToRiskRatio", "REAL");
		ensureColumnExists(conn, "Strategies", "requireTrendAlignment", "INTEGER");
		ensureColumnExists(conn, "Strategies", "orbWindowMinutes", "INTEGER");
		ensureColumnExists(conn, "Strategies", "breakoutBufferPct", "REAL");
		ensureColumnExists(conn, "Strategies", "minimumGapPct", "REAL");
		ensureColumnExists(conn, "Strategies", "reclaimWindowBars", "INTEGER");
		ensureColumnExists(conn, "Strategies", "entryBufferPct", "REAL");
		ensureColumnExists(conn, "Strategies", "stopBufferPct", "REAL");

		ensureStrategyRow(conn, defaultOrbConfig());
		ensureStrategyRow(conn, defaultIfvgConfig());
		ensureStrategyRow(conn, defaultVwapPullbackConfig());
		ensureStrategyRow(conn, defaultVwapMeanReversionConfig());
		ensureStrategyRow(conn, defaultGapGoConfig());
	}

	public static StrategySettings loadStrategySettings() {
		StrategySettings settings = new StrategySettings();
		settings.orb = defaultOrbConfig();
		settings.ifvg = defaultIfvgConfig();
		settings.vwapPullback = defaultVwapPullbackConfig();
		settings.vwapMeanReversion = defaultVwapMeanReversionConfig();
		settings.gapGo = defaultGapGoConfig();

		try (Connection conn = DatabaseManager.getConnection()) {
			initializeStrategyStore(conn);

			try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM Strategies");
				 ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					String strategyCode = normalizeStrategyCode(
						nonBlank(rs.getString("strategyCode"), rs.getString("strategyName"))
					);

					if (ORB_CODE.equals(strategyCode)) {
						settings.orb = readConfig(rs, settings.orb);
					} else if (IFVG_CODE.equals(strategyCode)) {
						settings.ifvg = readConfig(rs, settings.ifvg);
					} else if (VWAP_CODE.equals(strategyCode)) {
						settings.vwapPullback = readConfig(rs, settings.vwapPullback);
					} else if (MRVWAP_CODE.equals(strategyCode)) {
						settings.vwapMeanReversion = readConfig(rs, settings.vwapMeanReversion);
					} else if (GAPGO_CODE.equals(strategyCode)) {
						settings.gapGo = readConfig(rs, settings.gapGo);
					}
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return settings;
	}

	public static boolean saveStrategySettings(StrategySettings settings) {
		if (settings == null) {
			return false;
		}

		StrategyConfig orbConfig = normalizeConfig(settings.orb, defaultOrbConfig());
		StrategyConfig ifvgConfig = normalizeConfig(settings.ifvg, defaultIfvgConfig());
		StrategyConfig vwapConfig = normalizeConfig(settings.vwapPullback, defaultVwapPullbackConfig());
		StrategyConfig meanReversionConfig = normalizeConfig(settings.vwapMeanReversion, defaultVwapMeanReversionConfig());
		StrategyConfig gapGoConfig = normalizeConfig(settings.gapGo, defaultGapGoConfig());

		try (Connection conn = DatabaseManager.getConnection()) {
			initializeStrategyStore(conn);
			conn.setAutoCommit(false);

			try {
				saveConfig(conn, orbConfig);
				saveConfig(conn, ifvgConfig);
				saveConfig(conn, vwapConfig);
				saveConfig(conn, meanReversionConfig);
				saveConfig(conn, gapGoConfig);
				conn.commit();
				return true;
			} catch (SQLException e) {
				conn.rollback();
				e.printStackTrace();
				return false;
			} finally {
				conn.setAutoCommit(true);
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static String getStrategySettingsJson() {
		StrategySettings settings = loadStrategySettings();
		return "{"
			+ "\"orb\":" + configToJson(settings.orb) + ","
			+ "\"ifvg\":" + configToJson(settings.ifvg) + ","
			+ "\"vwapPullback\":" + configToJson(settings.vwapPullback) + ","
			+ "\"vwapMeanReversion\":" + configToJson(settings.vwapMeanReversion) + ","
			+ "\"gapGo\":" + configToJson(settings.gapGo) + ","
			+ "\"enabledStrategies\":" + enabledStrategiesJson(settings)
			+ "}";
	}

	public static StrategyBacktest buildStrategyBacktest(
		String symbol,
		LocalDate startDate,
		LocalDate endDate,
		double startingCapital,
		double perTradeBuyingPower,
		double takeProfit,
		double lossLimit
	) {
		return buildStrategyBacktest(
			loadStrategySettings(),
			symbol,
			startDate,
			endDate,
			startingCapital,
			perTradeBuyingPower,
			takeProfit,
			lossLimit
		);
	}

	public static StrategyBacktest buildStrategyBacktest(
		StrategySettings settings,
		String symbol,
		LocalDate startDate,
		LocalDate endDate,
		double startingCapital,
		double perTradeBuyingPower,
		double takeProfit,
		double lossLimit
	) {
		if (settings == null) {
			settings = loadStrategySettings();
		}
		if (!settings.hasEnabledStrategies()) {
			return null;
		}

		List<AlpacaManager.CachedBar> oneMinuteBars = AlpacaManager.loadCachedBars(symbol, startDate, endDate, "1Min");
		if (oneMinuteBars.isEmpty()) {
			return null;
		}

		LocalDate referenceStartDate = startDate.minusDays(10);
		List<AlpacaManager.CachedBar> fiveMinuteBars = loadSignalBars(settings, symbol, referenceStartDate, endDate, "5Min");
		List<AlpacaManager.CachedBar> thirtyMinuteBars = loadSignalBars(settings, symbol, referenceStartDate, endDate, "30Min");
		List<AlpacaManager.CachedBar> oneHourBars = loadSignalBars(settings, symbol, referenceStartDate, endDate, "1Hour");

		Map<LocalDate, List<AlpacaManager.CachedBar>> oneMinuteByDay = groupBarsByDay(oneMinuteBars);
		Map<LocalDate, List<AlpacaManager.CachedBar>> fiveMinuteByDay = groupBarsByDay(fiveMinuteBars);
		Map<LocalDate, List<AlpacaManager.CachedBar>> thirtyMinuteByDay = groupBarsByDay(thirtyMinuteBars);
		Map<LocalDate, List<AlpacaManager.CachedBar>> oneHourByDay = groupBarsByDay(oneHourBars);

		List<LocalDate> tradingDays = new ArrayList<LocalDate>(oneMinuteByDay.keySet());
		Collections.sort(tradingDays);

		BacktestLedger ledger = new BacktestLedger();
		ledger.currentCapital = roundToTwoDecimals(positiveOrDefault(startingCapital, 25000.0));
		ledger.peakEquity = ledger.currentCapital;
		double normalizedPerTradeBuyingPower = positiveOrDefault(perTradeBuyingPower, ledger.currentCapital);
		double normalizedTakeProfit = positiveOrDefault(takeProfit, 1000.0);
		double normalizedLossLimit = positiveOrDefault(lossLimit, 500.0);

		for (LocalDate tradingDay : tradingDays) {
			List<Signal> daySignals = new ArrayList<Signal>();
			List<AlpacaManager.CachedBar> dayOneMinuteBars = oneMinuteByDay.get(tradingDay);
			if (dayOneMinuteBars == null || dayOneMinuteBars.isEmpty()) {
				continue;
			}

			if (settings.orb.isEnabled) {
				Bias orbBias = resolveBias(selectTrendBars(settings.orb, thirtyMinuteBars, oneHourBars), tradingDay);
				List<Signal> orbSignals = findOrbSignals(dayOneMinuteBars, orbBias, settings.orb);
				if (!orbSignals.isEmpty()) {
					daySignals.addAll(orbSignals);
				}
			}

			if (settings.ifvg.isEnabled) {
				Bias ifvgBias = resolveBias(selectTrendBars(settings.ifvg, thirtyMinuteBars, oneHourBars), tradingDay);
				List<AlpacaManager.CachedBar> ifvgSignalBars = selectIfvgSignalBars(
					settings.ifvg,
					oneMinuteByDay,
					fiveMinuteByDay,
					thirtyMinuteByDay,
					tradingDay
				);
				List<Signal> ifvgSignals = findIfvgSignals(dayOneMinuteBars, ifvgSignalBars, ifvgBias, settings.ifvg);
				if (!ifvgSignals.isEmpty()) {
					daySignals.addAll(ifvgSignals);
				}
			}

			if (settings.vwapPullback.isEnabled) {
				Bias vwapBias = resolveBias(selectTrendBars(settings.vwapPullback, thirtyMinuteBars, oneHourBars), tradingDay);
				List<Signal> vwapSignals = findVwapPullbackSignals(dayOneMinuteBars, vwapBias, settings.vwapPullback);
				if (!vwapSignals.isEmpty()) {
					daySignals.addAll(vwapSignals);
				}
			}

			if (settings.vwapMeanReversion.isEnabled) {
				Bias meanReversionBias = resolveBias(selectTrendBars(settings.vwapMeanReversion, thirtyMinuteBars, oneHourBars), tradingDay);
				List<Signal> meanReversionSignals = findVwapMeanReversionSignals(dayOneMinuteBars, meanReversionBias, settings.vwapMeanReversion);
				if (!meanReversionSignals.isEmpty()) {
					daySignals.addAll(meanReversionSignals);
				}
			}

			if (settings.gapGo.isEnabled) {
				Bias gapGoBias = resolveBias(selectTrendBars(settings.gapGo, thirtyMinuteBars, oneHourBars), tradingDay);
				double previousClose = previousCloseBefore(oneMinuteBars, tradingDay);
				List<Signal> gapGoSignals = findGapGoSignals(dayOneMinuteBars, previousClose, gapGoBias, settings.gapGo);
				if (!gapGoSignals.isEmpty()) {
					daySignals.addAll(gapGoSignals);
				}
			}

			sortSignalsForExecution(daySignals);

			Map<String, Integer> tradesTakenByStrategy = new HashMap<String, Integer>();
			Map<String, Integer> entriesTakenByMinuteAndSide = new HashMap<String, Integer>();
			List<TradeRecord> activeTrades = new ArrayList<TradeRecord>();

			for (Signal signal : daySignals) {
				settleClosedTrades(activeTrades, signal.openedTime, ledger);
				StrategyConfig config = configForCode(settings, signal.strategyCode);
				if (config == null || !config.isEnabled) {
					continue;
				}
				int tradesTaken = tradesTakenForStrategy(tradesTakenByStrategy, signal.strategyCode);
				if (tradesTaken >= config.maxTradesPerDay) {
					continue;
				}
				if (hasConflictingActiveTrade(activeTrades, signal.side)) {
					continue;
				}
				if (activeTrades.size() >= MAX_CONCURRENT_TRADES_PER_SYMBOL) {
					continue;
				}
				if (entriesTakenByMinuteAndSide.containsKey(signalEntryBucket(signal))) {
					continue;
				}
				if (hasCrowdedActiveTrade(activeTrades, signal)) {
					continue;
				}

				TradeRecord trade = createTrade(
					signal,
					config,
					ledger.currentCapital,
					Math.max(0.0, ledger.currentCapital - reservedNotional(activeTrades)),
					normalizedPerTradeBuyingPower,
					normalizedTakeProfit,
					normalizedLossLimit
				);
				if (trade == null) {
					continue;
				}

				activeTrades.add(trade);
				entriesTakenByMinuteAndSide.put(signalEntryBucket(signal), 1);
				tradesTakenByStrategy.put(signal.strategyCode, tradesTaken + 1);
			}

			settleClosedTrades(activeTrades, null, ledger);
		}

		StrategyBacktest backtest = new StrategyBacktest();
		backtest.startDate = tradingDays.isEmpty() ? startDate : tradingDays.get(0);
		backtest.endDate = tradingDays.isEmpty() ? endDate : tradingDays.get(tradingDays.size() - 1);
		backtest.perTradeBuyingPower = roundToTwoDecimals(normalizedPerTradeBuyingPower);
		backtest.takeProfit = roundToTwoDecimals(normalizedTakeProfit);
		backtest.lossLimit = roundToTwoDecimals(normalizedLossLimit);
		backtest.endingCapital = roundToTwoDecimals(ledger.currentCapital);
		backtest.totalProfit = roundToTwoDecimals(ledger.currentCapital - startingCapital);
		backtest.returnPct = startingCapital <= 0.0 ? 0.0 : roundToTwoDecimals((backtest.totalProfit / startingCapital) * 100.0);
		backtest.winRate = ledger.trades.isEmpty() ? 0.0 : roundToTwoDecimals((ledger.winningTrades * 100.0) / ledger.trades.size());
		backtest.profitFactor = ledger.grossLoss == 0.0 ? roundToTwoDecimals(ledger.grossProfit) : roundToTwoDecimals(ledger.grossProfit / ledger.grossLoss);
		backtest.maxDrawdownPct = roundToTwoDecimals(ledger.maxDrawdownPct);
		backtest.timeframeSummary = buildTimeframeSummary(settings);
		backtest.trades = ledger.trades;
		return backtest;
	}

	public static List<LiveSignalSnapshot> evaluateLiveSignals(
		StrategySettings settings,
		List<AlpacaManager.CachedBar> dayOneMinuteBars,
		List<AlpacaManager.CachedBar> fiveMinuteBars,
		List<AlpacaManager.CachedBar> thirtyMinuteBars,
		List<AlpacaManager.CachedBar> oneHourBars,
		LocalDate tradingDay
	) {
		List<LiveSignalSnapshot> snapshots = new ArrayList<LiveSignalSnapshot>();
		StrategySettings activeSettings = settings == null ? loadStrategySettings() : settings;

		if (
			activeSettings == null
			|| tradingDay == null
			|| dayOneMinuteBars == null
			|| dayOneMinuteBars.isEmpty()
			|| !activeSettings.hasEnabledStrategies()
		) {
			return snapshots;
		}

		List<Signal> daySignals = new ArrayList<Signal>();
		Map<LocalDate, List<AlpacaManager.CachedBar>> oneMinuteByDay = groupBarsByDay(dayOneMinuteBars);
		Map<LocalDate, List<AlpacaManager.CachedBar>> fiveMinuteByDay = groupBarsByDay(fiveMinuteBars);
		Map<LocalDate, List<AlpacaManager.CachedBar>> thirtyMinuteByDay = groupBarsByDay(thirtyMinuteBars);
		List<AlpacaManager.CachedBar> currentDayOneMinuteBars = oneMinuteByDay.get(tradingDay);

		if (currentDayOneMinuteBars == null || currentDayOneMinuteBars.isEmpty()) {
			return snapshots;
		}

		if (activeSettings.orb.isEnabled) {
			Bias orbBias = resolveBias(selectTrendBars(activeSettings.orb, thirtyMinuteBars, oneHourBars), tradingDay);
			List<Signal> orbSignals = findOrbSignals(currentDayOneMinuteBars, orbBias, activeSettings.orb);
			if (!orbSignals.isEmpty()) {
				daySignals.addAll(orbSignals);
			}
		}

		if (activeSettings.ifvg.isEnabled) {
			Bias ifvgBias = resolveBias(selectTrendBars(activeSettings.ifvg, thirtyMinuteBars, oneHourBars), tradingDay);
			List<AlpacaManager.CachedBar> ifvgSignalBars = selectIfvgSignalBars(
				activeSettings.ifvg,
				oneMinuteByDay,
				fiveMinuteByDay,
				thirtyMinuteByDay,
				tradingDay
			);
			List<Signal> ifvgSignals = findIfvgSignals(currentDayOneMinuteBars, ifvgSignalBars, ifvgBias, activeSettings.ifvg);
			if (!ifvgSignals.isEmpty()) {
				daySignals.addAll(ifvgSignals);
			}
		}

		if (activeSettings.vwapPullback.isEnabled) {
			Bias vwapBias = resolveBias(selectTrendBars(activeSettings.vwapPullback, thirtyMinuteBars, oneHourBars), tradingDay);
			List<Signal> vwapSignals = findVwapPullbackSignals(currentDayOneMinuteBars, vwapBias, activeSettings.vwapPullback);
			if (!vwapSignals.isEmpty()) {
				daySignals.addAll(vwapSignals);
			}
		}

		if (activeSettings.vwapMeanReversion.isEnabled) {
			Bias meanReversionBias = resolveBias(selectTrendBars(activeSettings.vwapMeanReversion, thirtyMinuteBars, oneHourBars), tradingDay);
			List<Signal> meanReversionSignals = findVwapMeanReversionSignals(currentDayOneMinuteBars, meanReversionBias, activeSettings.vwapMeanReversion);
			if (!meanReversionSignals.isEmpty()) {
				daySignals.addAll(meanReversionSignals);
			}
		}

		if (activeSettings.gapGo.isEnabled) {
			Bias gapGoBias = resolveBias(selectTrendBars(activeSettings.gapGo, thirtyMinuteBars, oneHourBars), tradingDay);
			double previousClose = previousCloseBefore(thirtyMinuteBars, tradingDay);
			if (previousClose <= 0.0) {
				previousClose = previousCloseBefore(oneHourBars, tradingDay);
			}
			List<Signal> gapGoSignals = findGapGoSignals(currentDayOneMinuteBars, previousClose, gapGoBias, activeSettings.gapGo);
			if (!gapGoSignals.isEmpty()) {
				daySignals.addAll(gapGoSignals);
			}
		}

		sortSignalsForExecution(daySignals);

		for (int index = 0; index < daySignals.size(); index++) {
			snapshots.add(toLiveSignalSnapshot(daySignals.get(index)));
		}

		return snapshots;
	}

	private static StrategyConfig defaultOrbConfig() {
		StrategyConfig config = new StrategyConfig();
		config.strategyCode = ORB_CODE;
		config.strategyName = ORB_NAME;
		config.description = "Uses the morning opening range, waits for a clean breakout, then enters on the first reclaim when the higher timeframe bias agrees.";
		config.timeframe = "1Min";
		config.riskPerTradePct = 0.5;
		config.maxTradesPerDay = 2;
		config.isEnabled = true;
		config.trendTimeframe = "30Min";
		config.signalTimeframe = "1Min";
		config.rewardToRiskRatio = 0.75;
		config.requireTrendAlignment = true;
		config.orbWindowMinutes = 15;
		config.breakoutBufferPct = 0.01;
		config.minimumGapPct = 0.0;
		config.reclaimWindowBars = 3;
		config.entryBufferPct = 0.0;
		config.stopBufferPct = 0.03;
		return config;
	}

	private static StrategyConfig defaultIfvgConfig() {
		StrategyConfig config = new StrategyConfig();
		config.strategyCode = IFVG_CODE;
		config.strategyName = IFVG_NAME;
		config.description = "Uses a higher timeframe bias and looks for flipped intraday gaps that retest as support or resistance.";
		config.timeframe = "1Min";
		config.riskPerTradePct = 0.5;
		config.maxTradesPerDay = 5;
		config.isEnabled = true;
		config.trendTimeframe = "30Min";
		config.signalTimeframe = "5Min";
		config.rewardToRiskRatio = 0.75;
		config.requireTrendAlignment = false;
		config.orbWindowMinutes = 0;
		config.breakoutBufferPct = 0.0;
		config.minimumGapPct = 0.05;
		config.reclaimWindowBars = 8;
		config.entryBufferPct = 0.0;
		config.stopBufferPct = 0.03;
		return config;
	}

	private static StrategyConfig defaultVwapPullbackConfig() {
		StrategyConfig config = new StrategyConfig();
		config.strategyCode = VWAP_CODE;
		config.strategyName = VWAP_NAME;
		config.description = "Uses intraday VWAP as a trend filter, waits for a controlled pullback, then enters on a reclaim in the trend direction.";
		config.timeframe = "1Min";
		config.riskPerTradePct = 0.5;
		config.maxTradesPerDay = 5;
		config.isEnabled = true;
		config.trendTimeframe = "30Min";
		config.signalTimeframe = "1Min";
		config.rewardToRiskRatio = 0.75;
		config.requireTrendAlignment = true;
		config.orbWindowMinutes = 15;
		config.breakoutBufferPct = 0.0;
		config.minimumGapPct = 0.08;
		config.reclaimWindowBars = 3;
		config.entryBufferPct = 0.01;
		config.stopBufferPct = 0.03;
		return config;
	}

	private static StrategyConfig defaultVwapMeanReversionConfig() {
		StrategyConfig config = new StrategyConfig();
		config.strategyCode = MRVWAP_CODE;
		config.strategyName = MRVWAP_NAME;
		config.description = "Looks for stretched moves away from session VWAP with RSI exhaustion, then fades back toward VWAP after a confirming reversal candle.";
		config.timeframe = "1Min";
		config.riskPerTradePct = 0.5;
		config.maxTradesPerDay = 2;
		config.isEnabled = true;
		config.trendTimeframe = "30Min";
		config.signalTimeframe = "1Min";
		config.rewardToRiskRatio = 2.0;
		config.requireTrendAlignment = true;
		config.orbWindowMinutes = 15;
		config.breakoutBufferPct = 0.0;
		config.minimumGapPct = 1.5;
		config.reclaimWindowBars = 5;
		config.entryBufferPct = 0.0;
		config.stopBufferPct = 0.03;
		return config;
	}

	private static StrategyConfig defaultGapGoConfig() {
		StrategyConfig config = new StrategyConfig();
		config.strategyCode = GAPGO_CODE;
		config.strategyName = GAPGO_NAME;
		config.description = "Trades only with an overnight gap when the opening range confirms continuation instead of fading the gap.";
		config.timeframe = "1Min";
		config.riskPerTradePct = 0.5;
		config.maxTradesPerDay = 1;
		config.isEnabled = true;
		config.trendTimeframe = "30Min";
		config.signalTimeframe = "1Min";
		config.rewardToRiskRatio = 0.75;
		config.requireTrendAlignment = true;
		config.orbWindowMinutes = 5;
		config.breakoutBufferPct = 0.0;
		config.minimumGapPct = 0.5;
		config.reclaimWindowBars = 1;
		config.entryBufferPct = 0.0;
		config.stopBufferPct = 0.03;
		return config;
	}

	private static StrategyConfig normalizeConfig(StrategyConfig config, StrategyConfig defaults) {
		StrategyConfig normalized = defaults.copy();
		if (config == null) {
			return normalized;
		}

		normalized.strategyId = config.strategyId;
		normalized.strategyCode = defaults.strategyCode;
		normalized.strategyName = defaults.strategyName;
		normalized.description = defaults.description;
		normalized.timeframe = normalizeTimeframe(config.timeframe, defaults.timeframe);
		normalized.riskPerTradePct = boundedDouble(config.riskPerTradePct, defaults.riskPerTradePct, 0.1, 10.0);
		normalized.maxTradesPerDay = boundedInt(config.maxTradesPerDay, defaults.maxTradesPerDay, 1, 5);
		normalized.isEnabled = config.isEnabled;
		normalized.trendTimeframe = normalizeTrendTimeframe(config.trendTimeframe, defaults.trendTimeframe);
		normalized.signalTimeframe = normalizeSignalTimeframe(config.signalTimeframe, defaults.signalTimeframe);
		normalized.rewardToRiskRatio = boundedDouble(config.rewardToRiskRatio, defaults.rewardToRiskRatio, 0.25, 5.0);
		normalized.requireTrendAlignment = config.requireTrendAlignment;
		normalized.orbWindowMinutes = boundedInt(config.orbWindowMinutes, defaults.orbWindowMinutes, 5, 30);
		normalized.breakoutBufferPct = boundedDouble(config.breakoutBufferPct, defaults.breakoutBufferPct, 0.0, 1.0);
		normalized.minimumGapPct = boundedDouble(config.minimumGapPct, defaults.minimumGapPct, 0.005, 2.0);
		normalized.reclaimWindowBars = boundedInt(config.reclaimWindowBars, defaults.reclaimWindowBars, 1, 8);
		normalized.entryBufferPct = boundedDouble(config.entryBufferPct, defaults.entryBufferPct, 0.0, 1.0);
		normalized.stopBufferPct = boundedDouble(config.stopBufferPct, defaults.stopBufferPct, 0.0, 1.0);
		return normalized;
	}

	private static void saveConfig(Connection conn, StrategyConfig config) throws SQLException {
		String sql = "UPDATE Strategies SET "
			+ "strategyName = ?, "
			+ "description = ?, "
			+ "timeframe = ?, "
			+ "riskPerTradePct = ?, "
			+ "maxTradesPerDay = ?, "
			+ "isEnabled = ?, "
			+ "strategyCode = ?, "
			+ "trendTimeframe = ?, "
			+ "signalTimeframe = ?, "
			+ "rewardToRiskRatio = ?, "
			+ "requireTrendAlignment = ?, "
			+ "orbWindowMinutes = ?, "
			+ "breakoutBufferPct = ?, "
			+ "minimumGapPct = ?, "
			+ "reclaimWindowBars = ?, "
			+ "entryBufferPct = ?, "
			+ "stopBufferPct = ? "
			+ "WHERE strategyCode = ?";

		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, config.strategyName);
			pstmt.setString(2, config.description);
			pstmt.setString(3, config.timeframe);
			pstmt.setDouble(4, config.riskPerTradePct);
			pstmt.setInt(5, config.maxTradesPerDay);
			pstmt.setInt(6, config.isEnabled ? 1 : 0);
			pstmt.setString(7, config.strategyCode);
			pstmt.setString(8, config.trendTimeframe);
			pstmt.setString(9, config.signalTimeframe);
			pstmt.setDouble(10, config.rewardToRiskRatio);
			pstmt.setInt(11, config.requireTrendAlignment ? 1 : 0);
			pstmt.setInt(12, config.orbWindowMinutes);
			pstmt.setDouble(13, config.breakoutBufferPct);
			pstmt.setDouble(14, config.minimumGapPct);
			pstmt.setInt(15, config.reclaimWindowBars);
			pstmt.setDouble(16, config.entryBufferPct);
			pstmt.setDouble(17, config.stopBufferPct);
			pstmt.setString(18, config.strategyCode);
			pstmt.executeUpdate();
		}
	}

	private static StrategyConfig readConfig(ResultSet rs, StrategyConfig defaults) throws SQLException {
		StrategyConfig config = defaults.copy();
		config.strategyId = rs.getInt("strategyID");
		config.strategyCode = defaults.strategyCode;
		config.strategyName = readString(rs, "strategyName", defaults.strategyName);
		config.description = readString(rs, "description", defaults.description);
		config.timeframe = normalizeTimeframe(readString(rs, "timeframe", defaults.timeframe), defaults.timeframe);
		config.riskPerTradePct = boundedDouble(readDouble(rs, "riskPerTradePct", defaults.riskPerTradePct), defaults.riskPerTradePct, 0.1, 10.0);
		config.maxTradesPerDay = boundedInt(readInt(rs, "maxTradesPerDay", defaults.maxTradesPerDay), defaults.maxTradesPerDay, 1, 5);
		config.isEnabled = readBoolean(rs, "isEnabled", defaults.isEnabled);
		config.trendTimeframe = normalizeTrendTimeframe(readString(rs, "trendTimeframe", defaults.trendTimeframe), defaults.trendTimeframe);
		config.signalTimeframe = normalizeSignalTimeframe(readString(rs, "signalTimeframe", defaults.signalTimeframe), defaults.signalTimeframe);
		config.rewardToRiskRatio = boundedDouble(readDouble(rs, "rewardToRiskRatio", defaults.rewardToRiskRatio), defaults.rewardToRiskRatio, 0.25, 5.0);
		config.requireTrendAlignment = readBoolean(rs, "requireTrendAlignment", defaults.requireTrendAlignment);
		config.orbWindowMinutes = boundedInt(readInt(rs, "orbWindowMinutes", defaults.orbWindowMinutes), defaults.orbWindowMinutes, 5, 30);
		config.breakoutBufferPct = boundedDouble(readDouble(rs, "breakoutBufferPct", defaults.breakoutBufferPct), defaults.breakoutBufferPct, 0.0, 1.0);
		config.minimumGapPct = boundedDouble(readDouble(rs, "minimumGapPct", defaults.minimumGapPct), defaults.minimumGapPct, 0.005, 2.0);
		config.reclaimWindowBars = boundedInt(readInt(rs, "reclaimWindowBars", defaults.reclaimWindowBars), defaults.reclaimWindowBars, 1, 8);
		config.entryBufferPct = boundedDouble(readDouble(rs, "entryBufferPct", defaults.entryBufferPct), defaults.entryBufferPct, 0.0, 1.0);
		config.stopBufferPct = boundedDouble(readDouble(rs, "stopBufferPct", defaults.stopBufferPct), defaults.stopBufferPct, 0.0, 1.0);
		return config;
	}

	private static List<AlpacaManager.CachedBar> loadSignalBars(
		StrategySettings settings,
		String symbol,
		LocalDate startDate,
		LocalDate endDate,
		String timeframe
	) {
		boolean shouldLoad = false;
		if (settings.orb.isEnabled && timeframe.equals(settings.orb.trendTimeframe)) {
			shouldLoad = true;
		}
		if (settings.ifvg.isEnabled && (timeframe.equals(settings.ifvg.trendTimeframe) || timeframe.equals(settings.ifvg.signalTimeframe))) {
			shouldLoad = true;
		}
		if (settings.vwapPullback.isEnabled && timeframe.equals(settings.vwapPullback.trendTimeframe)) {
			shouldLoad = true;
		}
		if (settings.vwapMeanReversion.isEnabled && timeframe.equals(settings.vwapMeanReversion.trendTimeframe)) {
			shouldLoad = true;
		}
		if (settings.gapGo.isEnabled && timeframe.equals(settings.gapGo.trendTimeframe)) {
			shouldLoad = true;
		}

		if (!shouldLoad) {
			return new ArrayList<AlpacaManager.CachedBar>();
		}

		return AlpacaManager.loadCachedBars(symbol, startDate, endDate, timeframe);
	}

	private static Map<LocalDate, List<AlpacaManager.CachedBar>> groupBarsByDay(List<AlpacaManager.CachedBar> bars) {
		Map<LocalDate, List<AlpacaManager.CachedBar>> groupedBars = new HashMap<LocalDate, List<AlpacaManager.CachedBar>>();

		for (AlpacaManager.CachedBar bar : bars) {
			List<AlpacaManager.CachedBar> dayBars = groupedBars.get(bar.marketDate);
			if (dayBars == null) {
				dayBars = new ArrayList<AlpacaManager.CachedBar>();
				groupedBars.put(bar.marketDate, dayBars);
			}
			dayBars.add(bar);
		}

		return groupedBars;
	}

	private static List<AlpacaManager.CachedBar> selectTrendBars(
		StrategyConfig config,
		List<AlpacaManager.CachedBar> thirtyMinuteBars,
		List<AlpacaManager.CachedBar> oneHourBars
	) {
		if ("1Hour".equals(config.trendTimeframe)) {
			return oneHourBars;
		}
		return thirtyMinuteBars;
	}

	private static List<AlpacaManager.CachedBar> selectIfvgSignalBars(
		StrategyConfig config,
		Map<LocalDate, List<AlpacaManager.CachedBar>> oneMinuteByDay,
		Map<LocalDate, List<AlpacaManager.CachedBar>> fiveMinuteByDay,
		Map<LocalDate, List<AlpacaManager.CachedBar>> thirtyMinuteByDay,
		LocalDate tradingDay
	) {
		if ("1Min".equals(config.signalTimeframe)) {
			List<AlpacaManager.CachedBar> bars = oneMinuteByDay.get(tradingDay);
			return bars == null ? new ArrayList<AlpacaManager.CachedBar>() : bars;
		}
		if ("30Min".equals(config.signalTimeframe)) {
			List<AlpacaManager.CachedBar> bars = thirtyMinuteByDay.get(tradingDay);
			return bars == null ? new ArrayList<AlpacaManager.CachedBar>() : bars;
		}

		List<AlpacaManager.CachedBar> bars = fiveMinuteByDay.get(tradingDay);
		return bars == null ? new ArrayList<AlpacaManager.CachedBar>() : bars;
	}

	private static StrategyConfig configForCode(StrategySettings settings, String strategyCode) {
		if (settings == null || strategyCode == null) {
			return null;
		}
		if (ORB_CODE.equals(strategyCode)) {
			return settings.orb;
		}
		if (IFVG_CODE.equals(strategyCode)) {
			return settings.ifvg;
		}
		if (VWAP_CODE.equals(strategyCode)) {
			return settings.vwapPullback;
		}
		if (MRVWAP_CODE.equals(strategyCode)) {
			return settings.vwapMeanReversion;
		}
		if (GAPGO_CODE.equals(strategyCode)) {
			return settings.gapGo;
		}
		return null;
	}

	private static int tradesTakenForStrategy(Map<String, Integer> tradesTakenByStrategy, String strategyCode) {
		if (tradesTakenByStrategy == null || strategyCode == null) {
			return 0;
		}
		Integer count = tradesTakenByStrategy.get(strategyCode);
		return count == null ? 0 : count.intValue();
	}

	public static int maxConcurrentTradesPerSymbol() {
		return MAX_CONCURRENT_TRADES_PER_SYMBOL;
	}

	public static int minimumMinutesBetweenPortfolioEntries() {
		return MIN_MINUTES_BETWEEN_PORTFOLIO_ENTRIES;
	}

	public static double minimumDistinctEntryPricePct() {
		return MIN_DISTINCT_ENTRY_PRICE_PCT;
	}

	public static double liveSignalExecutionScore(LiveSignalSnapshot signal) {
		if (signal == null) {
			return 0.0;
		}
		if (signal.coordinationScore > 0.0) {
			return signal.coordinationScore;
		}
		double risk = Math.abs(signal.entryPrice - signal.stopPrice);
		double reward = Math.abs(signal.targetPrice - signal.entryPrice);
		double rewardRisk = risk <= 0.0 ? 0.0 : reward / risk;
		return roundToTwoDecimals(strategyPriority(signal.strategyCode) + (rewardRisk * 2.0));
	}

	private static void sortSignalsForExecution(List<Signal> signals) {
		if (signals == null || signals.isEmpty()) {
			return;
		}
		Collections.sort(signals, new Comparator<Signal>() {
			@Override
			public int compare(Signal first, Signal second) {
				int timeCompare = first.openedTime.compareTo(second.openedTime);
				if (timeCompare != 0) {
					return timeCompare;
				}
				int sideCompare = safeString(first.side).compareTo(safeString(second.side));
				if (sideCompare != 0) {
					return sideCompare;
				}
				int scoreCompare = Double.compare(second.coordinationScore, first.coordinationScore);
				if (scoreCompare != 0) {
					return scoreCompare;
				}
				return safeString(first.strategyCode).compareTo(safeString(second.strategyCode));
			}
		});
	}

	private static double signalExecutionScore(Signal signal) {
		if (signal == null) {
			return 0.0;
		}

		double risk = Math.abs(signal.entryPrice - signal.stopPrice);
		double reward = Math.abs(signal.targetPrice - signal.entryPrice);
		double rewardRisk = risk <= 0.0 ? 0.0 : reward / risk;
		double score = strategyPriority(signal.strategyCode) + (rewardRisk * 2.0);

		if (signal.bars != null && signal.entryIndex > 0 && signal.entryIndex < signal.bars.size()) {
			AlpacaManager.CachedBar entryBar = signal.bars.get(signal.entryIndex);
			double range = Math.max(0.0, entryBar.high - entryBar.low);
			if (range > 0.0) {
				score += Math.min(1.0, Math.abs(entryBar.close - entryBar.open) / range);
				score += closePosition(entryBar, signal.side);
			}

			double recentAverageVolume = averageVolume(signal.bars, Math.max(0, signal.entryIndex - 20), signal.entryIndex - 1);
			if (recentAverageVolume > 0.0) {
				score += Math.min(2.0, entryBar.volume / recentAverageVolume) * 0.5;
			}
		}

		return roundToTwoDecimals(score);
	}

	private static boolean isRecentVolatilityAllowed(Signal signal) {
		if (signal == null || signal.bars == null || signal.entryIndex <= 0) {
			return true;
		}

		int startIndex = Math.max(0, signal.entryIndex - VOLATILITY_LOOKBACK_BARS);
		int endIndex = signal.entryIndex - 1;
		double totalRangePct = 0.0;
		int count = 0;
		for (int index = startIndex; index <= endIndex && index < signal.bars.size(); index++) {
			AlpacaManager.CachedBar bar = signal.bars.get(index);
			if (bar == null || bar.close <= 0.0) {
				continue;
			}
			double rangePct = ((bar.high - bar.low) / Math.max(1.0, bar.close)) * 100.0;
			if (rangePct <= 0.0) {
				continue;
			}
			totalRangePct += rangePct;
			count++;
		}

		if (count < Math.min(5, VOLATILITY_LOOKBACK_BARS)) {
			return true;
		}

		double averageRangePct = totalRangePct / count;
		return averageRangePct <= MAX_RECENT_RANGE_PCT;
	}

	private static boolean isWithinTimeWindow(LocalTime time, LocalTime start, LocalTime end) {
		return time != null && start != null && end != null && !time.isBefore(start) && time.isBefore(end);
	}

	private static double strategyPriority(String strategyCode) {
		if (IFVG_CODE.equals(strategyCode)) {
			return 3.0;
		}
		if (GAPGO_CODE.equals(strategyCode)) {
			return 2.6;
		}
		if (ORB_CODE.equals(strategyCode)) {
			return 2.2;
		}
		if (VWAP_CODE.equals(strategyCode)) {
			return 1.8;
		}
		if (MRVWAP_CODE.equals(strategyCode)) {
			return 1.2;
		}
		return 1.0;
	}

	private static String signalEntryBucket(Signal signal) {
		if (signal == null) {
			return "";
		}
		return safeString(signal.openedAt) + "|" + safeString(signal.side);
	}

	private static boolean hasCrowdedActiveTrade(List<TradeRecord> activeTrades, Signal signal) {
		if (activeTrades == null || activeTrades.isEmpty() || signal == null) {
			return false;
		}

		for (int index = 0; index < activeTrades.size(); index++) {
			TradeRecord trade = activeTrades.get(index);
			if (trade == null || trade.side == null || !trade.side.equals(signal.side)) {
				continue;
			}
			if (minutesBetween(trade.openedTime, signal.openedTime) < MIN_MINUTES_BETWEEN_PORTFOLIO_ENTRIES) {
				return true;
			}
			if (entryPriceDistancePct(trade.entryPrice, signal.entryPrice) < MIN_DISTINCT_ENTRY_PRICE_PCT) {
				return true;
			}
		}

		return false;
	}

	private static long minutesBetween(LocalTime first, LocalTime second) {
		if (first == null || second == null) {
			return Long.MAX_VALUE;
		}
		return Math.abs(Duration.between(first, second).toMinutes());
	}

	private static double entryPriceDistancePct(double firstPrice, double secondPrice) {
		double referencePrice = Math.max(1.0, Math.abs(secondPrice));
		return Math.abs(firstPrice - secondPrice) / referencePrice * 100.0;
	}

	private static void settleClosedTrades(
		List<TradeRecord> activeTrades,
		LocalTime upToTime,
		BacktestLedger ledger
	) {
		if (activeTrades == null || activeTrades.isEmpty() || ledger == null) {
			return;
		}

		Collections.sort(activeTrades, new Comparator<TradeRecord>() {
			@Override
			public int compare(TradeRecord first, TradeRecord second) {
				return first.closedTime.compareTo(second.closedTime);
			}
		});

		while (!activeTrades.isEmpty()) {
			TradeRecord trade = activeTrades.get(0);
			if (upToTime != null && trade.closedTime.isAfter(upToTime)) {
				break;
			}
			activeTrades.remove(0);
			recordTradeClose(ledger, trade);
		}
	}

	private static void recordTradeClose(BacktestLedger ledger, TradeRecord trade) {
		if (ledger == null || trade == null) {
			return;
		}

		ledger.trades.add(trade);
		ledger.currentCapital = roundToTwoDecimals(ledger.currentCapital + trade.pnl);
		if (trade.pnl >= 0.0) {
			ledger.grossProfit = roundToTwoDecimals(ledger.grossProfit + trade.pnl);
			ledger.winningTrades++;
		} else {
			ledger.grossLoss = roundToTwoDecimals(ledger.grossLoss + Math.abs(trade.pnl));
		}

		if (ledger.currentCapital > ledger.peakEquity) {
			ledger.peakEquity = ledger.currentCapital;
		}
		if (ledger.peakEquity > 0.0) {
			double currentDrawdown = ((ledger.peakEquity - ledger.currentCapital) / ledger.peakEquity) * 100.0;
			if (currentDrawdown > ledger.maxDrawdownPct) {
				ledger.maxDrawdownPct = currentDrawdown;
			}
		}
	}

	private static double reservedNotional(List<TradeRecord> activeTrades) {
		if (activeTrades == null || activeTrades.isEmpty()) {
			return 0.0;
		}

		double reserved = 0.0;
		for (int index = 0; index < activeTrades.size(); index++) {
			TradeRecord trade = activeTrades.get(index);
			reserved += Math.max(0.0, trade.entryPrice * trade.qty);
		}
		return roundToTwoDecimals(reserved);
	}

	private static boolean hasConflictingActiveTrade(List<TradeRecord> activeTrades, String side) {
		if (activeTrades == null || activeTrades.isEmpty() || side == null) {
			return false;
		}
		for (int index = 0; index < activeTrades.size(); index++) {
			TradeRecord trade = activeTrades.get(index);
			if (trade.side != null && !trade.side.equals(side)) {
				return true;
			}
		}
		return false;
	}

	private static Bias resolveBias(List<AlpacaManager.CachedBar> trendBars, LocalDate tradingDay) {
		if (trendBars == null || trendBars.isEmpty()) {
			return Bias.NEUTRAL;
		}

		List<AlpacaManager.CachedBar> previousBars = new ArrayList<AlpacaManager.CachedBar>();
		for (AlpacaManager.CachedBar bar : trendBars) {
			if (bar.marketDate.isBefore(tradingDay)) {
				previousBars.add(bar);
			}
		}

		if (previousBars.size() < 2) {
			return Bias.NEUTRAL;
		}

		AlpacaManager.CachedBar lastBar = previousBars.get(previousBars.size() - 1);
		AlpacaManager.CachedBar priorBar = previousBars.get(previousBars.size() - 2);

		boolean bullish = lastBar.close > lastBar.open && priorBar.close > priorBar.open;
		boolean bearish = lastBar.close < lastBar.open && priorBar.close < priorBar.open;

		if (bullish) {
			return Bias.BULLISH;
		}
		if (bearish) {
			return Bias.BEARISH;
		}
		return Bias.NEUTRAL;
	}

	private static List<Signal> findOrbSignals(List<AlpacaManager.CachedBar> dayBars, Bias bias, StrategyConfig config) {
		List<Signal> signals = new ArrayList<Signal>();
		if (dayBars == null || dayBars.size() <= config.orbWindowMinutes) {
			return signals;
		}
		if (config.requireTrendAlignment && bias == Bias.NEUTRAL) {
			return signals;
		}

		double rangeHigh = Double.NEGATIVE_INFINITY;
		double rangeLow = Double.POSITIVE_INFINITY;

		for (int index = 0; index < config.orbWindowMinutes && index < dayBars.size(); index++) {
			AlpacaManager.CachedBar bar = dayBars.get(index);
			if (bar.high > rangeHigh) {
				rangeHigh = bar.high;
			}
			if (bar.low < rangeLow) {
				rangeLow = bar.low;
			}
		}

		double rangeSize = roundToTwoDecimals(rangeHigh - rangeLow);
		if (rangeSize <= 0.0) {
			return signals;
		}

		double openingRangeAverageVolume = averageVolume(dayBars, 0, Math.max(0, config.orbWindowMinutes - 1));
		double longBreakoutBuffer = percentOfPrice(rangeHigh, config.breakoutBufferPct);
		double shortBreakoutBuffer = percentOfPrice(rangeLow, config.breakoutBufferPct);

		if (!config.requireTrendAlignment || bias == Bias.BULLISH) {
			addSignalIfUnique(
				signals,
				buildOrbOpeningDriveSignal(
					dayBars,
					"LONG",
					rangeHigh,
					rangeLow,
					rangeSize,
					openingRangeAverageVolume,
					config,
					bias
				)
			);
		}
		if (!config.requireTrendAlignment || bias == Bias.BEARISH) {
			addSignalIfUnique(
				signals,
				buildOrbOpeningDriveSignal(
					dayBars,
					"SHORT",
					rangeHigh,
					rangeLow,
					rangeSize,
					openingRangeAverageVolume,
					config,
					bias
				)
			);
		}

		if (!config.requireTrendAlignment || bias == Bias.BULLISH) {
			for (int index = config.orbWindowMinutes; index < dayBars.size(); index++) {
				AlpacaManager.CachedBar entryBar = dayBars.get(index);
				if (entryBar.marketTime.isAfter(ORB_ENTRY_CUTOFF)) {
					break;
				}
				if (!isOrbBreakoutBar(entryBar, "LONG", rangeHigh, longBreakoutBuffer, rangeSize, openingRangeAverageVolume)) {
					continue;
				}

				addSignalIfUnique(signals, buildOrbRetestSignal(dayBars, index, "LONG", rangeHigh, rangeLow, config, bias));
			}
		}

		if (!config.requireTrendAlignment || bias == Bias.BEARISH) {
			for (int index = config.orbWindowMinutes; index < dayBars.size(); index++) {
				AlpacaManager.CachedBar entryBar = dayBars.get(index);
				if (entryBar.marketTime.isAfter(ORB_ENTRY_CUTOFF)) {
					break;
				}
				if (!isOrbBreakoutBar(entryBar, "SHORT", rangeLow, shortBreakoutBuffer, rangeSize, openingRangeAverageVolume)) {
					continue;
				}

				addSignalIfUnique(signals, buildOrbRetestSignal(dayBars, index, "SHORT", rangeHigh, rangeLow, config, bias));
			}
		}

		Collections.sort(signals, new Comparator<Signal>() {
			@Override
			public int compare(Signal first, Signal second) {
				return first.openedTime.compareTo(second.openedTime);
			}
		});
		return dedupeSignals(signals);
	}

	private static List<Signal> findIfvgSignals(
		List<AlpacaManager.CachedBar> dayOneMinuteBars,
		List<AlpacaManager.CachedBar> signalBars,
		Bias bias,
		StrategyConfig config
	) {
		List<Signal> signals = new ArrayList<Signal>();
		if (dayOneMinuteBars == null || dayOneMinuteBars.isEmpty() || signalBars == null || signalBars.size() < 4) {
			return signals;
		}
		if (config.requireTrendAlignment && bias == Bias.NEUTRAL) {
			return signals;
		}
		Map<LocalTime, Double> vwapByTime = buildSessionVwapMap(dayOneMinuteBars);

		if (!config.requireTrendAlignment || bias == Bias.BULLISH) {
			signals.addAll(findBullishIfvgSignals(dayOneMinuteBars, signalBars, vwapByTime, config, bias));
		}
		if (!config.requireTrendAlignment || bias == Bias.BEARISH) {
			signals.addAll(findBearishIfvgSignals(dayOneMinuteBars, signalBars, vwapByTime, config, bias));
		}

		Collections.sort(signals, new Comparator<Signal>() {
			@Override
			public int compare(Signal first, Signal second) {
				return first.openedTime.compareTo(second.openedTime);
			}
		});
		return dedupeSignals(signals);
	}

	private static List<Signal> findVwapPullbackSignals(
		List<AlpacaManager.CachedBar> dayOneMinuteBars,
		Bias bias,
		StrategyConfig config
	) {
		List<Signal> signals = new ArrayList<Signal>();
		if (dayOneMinuteBars == null || dayOneMinuteBars.size() < 25) {
			return signals;
		}
		if (config.requireTrendAlignment && bias == Bias.NEUTRAL) {
			return signals;
		}

		Map<LocalTime, Double> vwapByTime = buildSessionVwapMap(dayOneMinuteBars);
		double[] ema9 = buildEmaValues(dayOneMinuteBars, 9);
		double[] ema20 = buildEmaValues(dayOneMinuteBars, 20);

		for (int index = 20; index < dayOneMinuteBars.size(); index++) {
			AlpacaManager.CachedBar entryBar = dayOneMinuteBars.get(index);
			if (entryBar.marketTime.isBefore(VWAP_ENTRY_START)) {
				continue;
			}
			if (entryBar.marketTime.isAfter(VWAP_ENTRY_CUTOFF)) {
				break;
			}
			if (isWithinTimeWindow(entryBar.marketTime, VWAP_OPEN_CHOP_START, VWAP_OPEN_CHOP_END)) {
				continue;
			}

			if (!config.requireTrendAlignment || bias == Bias.BULLISH) {
				addSignalIfUnique(
					signals,
					buildVwapPullbackSignal(dayOneMinuteBars, index, "LONG", vwapByTime, ema9, ema20, config, bias)
				);
			}
			if (!config.requireTrendAlignment || bias == Bias.BEARISH) {
				addSignalIfUnique(
					signals,
					buildVwapPullbackSignal(dayOneMinuteBars, index, "SHORT", vwapByTime, ema9, ema20, config, bias)
				);
			}
		}

		Collections.sort(signals, new Comparator<Signal>() {
			@Override
			public int compare(Signal first, Signal second) {
				return first.openedTime.compareTo(second.openedTime);
			}
		});
		return dedupeSignals(signals);
	}

	private static List<Signal> findVwapMeanReversionSignals(
		List<AlpacaManager.CachedBar> dayOneMinuteBars,
		Bias bias,
		StrategyConfig config
	) {
		List<Signal> signals = new ArrayList<Signal>();
		if (dayOneMinuteBars == null || dayOneMinuteBars.size() < 25) {
			return signals;
		}
		if (config.requireTrendAlignment && bias != Bias.NEUTRAL) {
			return signals;
		}

		Map<LocalTime, Double> vwapByTime = buildSessionVwapMap(dayOneMinuteBars);
		double[] rsi14 = buildRsiValues(dayOneMinuteBars, 14);

		for (int index = 20; index < dayOneMinuteBars.size(); index++) {
			AlpacaManager.CachedBar entryBar = dayOneMinuteBars.get(index);
			if (entryBar.marketTime.isBefore(MRVWAP_ENTRY_START)) {
				continue;
			}
			if (entryBar.marketTime.isAfter(MRVWAP_ENTRY_CUTOFF)) {
				break;
			}
			if (isWithinTimeWindow(entryBar.marketTime, MRVWAP_LATE_MORNING_CHOP_START, MRVWAP_LATE_MORNING_CHOP_END)) {
				continue;
			}

			if (!config.requireTrendAlignment || bias != Bias.BEARISH) {
				addSignalIfUnique(
					signals,
					buildVwapMeanReversionSignal(dayOneMinuteBars, index, "LONG", vwapByTime, rsi14, config, bias)
				);
			}
			if (!config.requireTrendAlignment || bias != Bias.BULLISH) {
				addSignalIfUnique(
					signals,
					buildVwapMeanReversionSignal(dayOneMinuteBars, index, "SHORT", vwapByTime, rsi14, config, bias)
				);
			}
		}

		Collections.sort(signals, new Comparator<Signal>() {
			@Override
			public int compare(Signal first, Signal second) {
				return first.openedTime.compareTo(second.openedTime);
			}
		});
		return dedupeSignals(signals);
	}

	private static List<Signal> findGapGoSignals(
		List<AlpacaManager.CachedBar> dayOneMinuteBars,
		double previousClose,
		Bias bias,
		StrategyConfig config
	) {
		List<Signal> signals = new ArrayList<Signal>();
		if (dayOneMinuteBars == null || dayOneMinuteBars.size() <= config.orbWindowMinutes || previousClose <= 0.0) {
			return signals;
		}
		if (config.requireTrendAlignment && bias == Bias.NEUTRAL) {
			return signals;
		}

		AlpacaManager.CachedBar sessionOpenBar = dayOneMinuteBars.get(0);
		double sessionOpen = roundToTwoDecimals(sessionOpenBar.open);
		double gapPct = roundToTwoDecimals(((sessionOpen - previousClose) / previousClose) * 100.0);
		boolean gapUp = gapPct >= config.minimumGapPct;
		boolean gapDown = gapPct <= -config.minimumGapPct;
		if (!gapUp && !gapDown) {
			return signals;
		}
		if (config.requireTrendAlignment && gapUp && bias == Bias.BEARISH) {
			return signals;
		}
		if (config.requireTrendAlignment && gapDown && bias == Bias.BULLISH) {
			return signals;
		}

		double rangeHigh = Double.NEGATIVE_INFINITY;
		double rangeLow = Double.POSITIVE_INFINITY;
		int openingRangeBars = Math.min(Math.max(1, config.orbWindowMinutes), dayOneMinuteBars.size());
		for (int index = 0; index < openingRangeBars; index++) {
			AlpacaManager.CachedBar bar = dayOneMinuteBars.get(index);
			rangeHigh = Math.max(rangeHigh, bar.high);
			rangeLow = Math.min(rangeLow, bar.low);
		}

		double rangeSize = roundToTwoDecimals(rangeHigh - rangeLow);
		if (rangeSize <= 0.0) {
			return signals;
		}

		double openingRangeAverageVolume = averageVolume(dayOneMinuteBars, 0, openingRangeBars - 1);
		for (int index = openingRangeBars; index < dayOneMinuteBars.size(); index++) {
			AlpacaManager.CachedBar entryBar = dayOneMinuteBars.get(index);
			if (entryBar.marketTime.isAfter(GAPGO_ENTRY_CUTOFF)) {
				break;
			}

			if (gapUp && (!config.requireTrendAlignment || bias != Bias.BEARISH)) {
				Signal longSignal = buildGapGoSignal(
					dayOneMinuteBars,
					index,
					"LONG",
					previousClose,
					gapPct,
					rangeHigh,
					rangeLow,
					rangeSize,
					openingRangeAverageVolume,
					config,
					bias
				);
				if (longSignal != null) {
					signals.add(longSignal);
					break;
				}
			}

			if (gapDown && (!config.requireTrendAlignment || bias != Bias.BULLISH)) {
				Signal shortSignal = buildGapGoSignal(
					dayOneMinuteBars,
					index,
					"SHORT",
					previousClose,
					gapPct,
					rangeHigh,
					rangeLow,
					rangeSize,
					openingRangeAverageVolume,
					config,
					bias
				);
				if (shortSignal != null) {
					signals.add(shortSignal);
					break;
				}
			}
		}

		return dedupeSignals(signals);
	}

	private static List<Signal> findBullishIfvgSignals(
		List<AlpacaManager.CachedBar> dayOneMinuteBars,
		List<AlpacaManager.CachedBar> signalBars,
		Map<LocalTime, Double> vwapByTime,
		StrategyConfig config,
		Bias bias
	) {
		List<Signal> signals = new ArrayList<Signal>();

		for (int index = 2; index < signalBars.size(); index++) {
			AlpacaManager.CachedBar firstBar = signalBars.get(index - 2);
			AlpacaManager.CachedBar impulseBar = signalBars.get(index - 1);
			AlpacaManager.CachedBar thirdBar = signalBars.get(index);
			if (firstBar.low <= thirdBar.high) {
				continue;
			}
			if (!isIfvgImpulseBar(firstBar, impulseBar, signalBars, index, "LONG")) {
				continue;
			}

			double gapLow = thirdBar.high;
			double gapHigh = firstBar.low;
			double gapWidth = gapHigh - gapLow;
			double gapPct = ((gapHigh - gapLow) / Math.max(1.0, gapLow)) * 100.0;
			if (gapPct < config.minimumGapPct) {
				continue;
			}

			int reclaimLimit = Math.min(signalBars.size() - 1, index + config.reclaimWindowBars);
			for (int reclaimIndex = index + 1; reclaimIndex <= reclaimLimit; reclaimIndex++) {
				AlpacaManager.CachedBar reclaimBar = signalBars.get(reclaimIndex);
				if (reclaimBar.marketTime.isBefore(IFVG_LONG_ENTRY_START)) {
					continue;
				}
				if (reclaimBar.marketTime.isAfter(IFVG_ENTRY_CUTOFF)) {
					break;
				}

				double flipBuffer = percentOfPrice(gapHigh, config.entryBufferPct);
				if (reclaimBar.close <= gapHigh + flipBuffer) {
					continue;
				}
				if (reclaimBar.close < reclaimBar.open) {
					continue;
				}
				if (closePosition(reclaimBar, "LONG") < MIN_RECLAIM_CLOSE_POSITION) {
					continue;
				}
				if (reclaimBar.volume < averageVolume(signalBars, Math.max(0, reclaimIndex - 3), reclaimIndex - 1) * MIN_IFVG_RECLAIM_VOLUME_RATIO) {
					continue;
				}
				if ((reclaimBar.close - gapHigh) > (gapWidth * MAX_RECLAIM_EXTENSION_GAP_MULTIPLE)) {
					continue;
				}

				LocalTime entryCutoff = limitRetestCutoff(reclaimBar.marketTime, config.signalTimeframe, config.reclaimWindowBars);
				int entryIndex = findRetestEntryIndex(
					dayOneMinuteBars,
					reclaimBar.marketTime,
					entryCutoff,
					"LONG",
					gapLow,
					gapHigh,
					config.entryBufferPct
				);
				if (entryIndex < 0) {
					continue;
				}

				AlpacaManager.CachedBar entryBar = dayOneMinuteBars.get(entryIndex);
				double entryVwap = vwapAtTime(vwapByTime, entryBar.marketTime);
				if (entryVwap > 0.0 && entryBar.close < entryVwap) {
					continue;
				}
				double stopPrice = roundToTwoDecimals(gapLow - percentOfPrice(gapLow, config.stopBufferPct));
				if (entryBar.close <= stopPrice) {
					continue;
				}

				double targetPrice = roundToTwoDecimals(entryBar.close + ((entryBar.close - stopPrice) * config.rewardToRiskRatio));
				addSignalIfUnique(
					signals,
					simulateSignal(
						IFVG_CODE,
						"LONG",
						dayOneMinuteBars,
						entryIndex,
						entryBar.close,
						stopPrice,
						targetPrice,
						0.0,
						buildIfvgNotes("LONG", config, gapLow, gapHigh, bias)
					)
				);
				break;
			}
		}

		return signals;
	}

	private static List<Signal> findBearishIfvgSignals(
		List<AlpacaManager.CachedBar> dayOneMinuteBars,
		List<AlpacaManager.CachedBar> signalBars,
		Map<LocalTime, Double> vwapByTime,
		StrategyConfig config,
		Bias bias
	) {
		List<Signal> signals = new ArrayList<Signal>();

		for (int index = 2; index < signalBars.size(); index++) {
			AlpacaManager.CachedBar firstBar = signalBars.get(index - 2);
			AlpacaManager.CachedBar impulseBar = signalBars.get(index - 1);
			AlpacaManager.CachedBar thirdBar = signalBars.get(index);
			if (firstBar.high >= thirdBar.low) {
				continue;
			}
			if (!isIfvgImpulseBar(firstBar, impulseBar, signalBars, index, "SHORT")) {
				continue;
			}

			double gapLow = firstBar.high;
			double gapHigh = thirdBar.low;
			double gapWidth = gapHigh - gapLow;
			double gapPct = ((gapHigh - gapLow) / Math.max(1.0, gapLow)) * 100.0;
			if (gapPct < config.minimumGapPct) {
				continue;
			}

			int reclaimLimit = Math.min(signalBars.size() - 1, index + config.reclaimWindowBars);
			for (int reclaimIndex = index + 1; reclaimIndex <= reclaimLimit; reclaimIndex++) {
				AlpacaManager.CachedBar reclaimBar = signalBars.get(reclaimIndex);
				if (reclaimBar.marketTime.isBefore(IFVG_SHORT_ENTRY_START)) {
					continue;
				}
				if (bias == Bias.NEUTRAL && reclaimBar.marketTime.isAfter(IFVG_NEUTRAL_SHORT_ENTRY_CUTOFF)) {
					break;
				}
				if (reclaimBar.marketTime.isAfter(IFVG_ENTRY_CUTOFF)) {
					break;
				}

				double flipBuffer = percentOfPrice(gapLow, config.entryBufferPct);
				if (reclaimBar.close >= gapLow - flipBuffer) {
					continue;
				}
				if (reclaimBar.close > reclaimBar.open) {
					continue;
				}
				if (closePosition(reclaimBar, "SHORT") < MIN_RECLAIM_CLOSE_POSITION) {
					continue;
				}
				double reclaimVolumeRatio = bias == Bias.NEUTRAL
					? MIN_NEUTRAL_SHORT_RECLAIM_VOLUME_RATIO
					: MIN_IFVG_RECLAIM_VOLUME_RATIO;
				if (reclaimBar.volume < averageVolume(signalBars, Math.max(0, reclaimIndex - 3), reclaimIndex - 1) * reclaimVolumeRatio) {
					continue;
				}
				if ((gapLow - reclaimBar.close) > (gapWidth * MAX_RECLAIM_EXTENSION_GAP_MULTIPLE)) {
					continue;
				}

				LocalTime entryCutoff = limitRetestCutoff(reclaimBar.marketTime, config.signalTimeframe, config.reclaimWindowBars);
				int entryIndex = findRetestEntryIndex(
					dayOneMinuteBars,
					reclaimBar.marketTime,
					entryCutoff,
					"SHORT",
					gapLow,
					gapHigh,
					config.entryBufferPct
				);
				if (entryIndex < 0) {
					continue;
				}

				AlpacaManager.CachedBar entryBar = dayOneMinuteBars.get(entryIndex);
				double entryVwap = vwapAtTime(vwapByTime, entryBar.marketTime);
				if (entryVwap > 0.0 && entryBar.close > entryVwap) {
					continue;
				}
				double stopPrice = roundToTwoDecimals(gapHigh + percentOfPrice(gapHigh, config.stopBufferPct));
				if (entryBar.close >= stopPrice) {
					continue;
				}

				double targetPrice = roundToTwoDecimals(entryBar.close - ((stopPrice - entryBar.close) * config.rewardToRiskRatio));
				addSignalIfUnique(
					signals,
					simulateSignal(
						IFVG_CODE,
						"SHORT",
						dayOneMinuteBars,
						entryIndex,
						entryBar.close,
						stopPrice,
						targetPrice,
						0.0,
						buildIfvgNotes("SHORT", config, gapLow, gapHigh, bias)
					)
				);
				break;
			}
		}

		return signals;
	}

	private static Signal simulateSignal(
		String strategyCode,
		String side,
		List<AlpacaManager.CachedBar> bars,
		int entryIndex,
		double entryPrice,
		double stopPrice,
		double targetPrice,
		double failureExitPrice,
		String tradeNotes
	) {
		if (bars == null || bars.isEmpty() || entryIndex < 0 || entryIndex >= bars.size()) {
			return null;
		}

		AlpacaManager.CachedBar entryBar = bars.get(entryIndex);
		double exitPrice = roundToTwoDecimals(bars.get(bars.size() - 1).close);
		String closedAt = bars.get(bars.size() - 1).displayTime;
		LocalTime closedTime = bars.get(bars.size() - 1).marketTime;

		for (int index = entryIndex + 1; index < bars.size(); index++) {
			AlpacaManager.CachedBar bar = bars.get(index);

			if ("LONG".equals(side)) {
				if (bar.low <= stopPrice && bar.high >= targetPrice) {
					exitPrice = roundToTwoDecimals(stopPrice);
					closedAt = bar.displayTime;
					closedTime = bar.marketTime;
					break;
				}
				if (bar.low <= stopPrice) {
					exitPrice = roundToTwoDecimals(stopPrice);
					closedAt = bar.displayTime;
					closedTime = bar.marketTime;
					break;
				}
				if (bar.high >= targetPrice) {
					exitPrice = roundToTwoDecimals(targetPrice);
					closedAt = bar.displayTime;
					closedTime = bar.marketTime;
					break;
				}
				if (failureExitPrice > 0.0 && bar.close <= failureExitPrice) {
					exitPrice = roundToTwoDecimals(bar.close);
					closedAt = bar.displayTime;
					closedTime = bar.marketTime;
					break;
				}
			} else {
				if (bar.high >= stopPrice && bar.low <= targetPrice) {
					exitPrice = roundToTwoDecimals(stopPrice);
					closedAt = bar.displayTime;
					closedTime = bar.marketTime;
					break;
				}
				if (bar.high >= stopPrice) {
					exitPrice = roundToTwoDecimals(stopPrice);
					closedAt = bar.displayTime;
					closedTime = bar.marketTime;
					break;
				}
				if (bar.low <= targetPrice) {
					exitPrice = roundToTwoDecimals(targetPrice);
					closedAt = bar.displayTime;
					closedTime = bar.marketTime;
					break;
				}
				if (failureExitPrice > 0.0 && bar.close >= failureExitPrice) {
					exitPrice = roundToTwoDecimals(bar.close);
					closedAt = bar.displayTime;
					closedTime = bar.marketTime;
					break;
				}
			}
		}

		Signal signal = new Signal();
		signal.strategyCode = strategyCode;
		signal.side = side;
		signal.entryPrice = roundToTwoDecimals(entryPrice);
		signal.stopPrice = roundToTwoDecimals(stopPrice);
		signal.targetPrice = roundToTwoDecimals(targetPrice);
		signal.exitPrice = exitPrice;
		signal.openedAt = entryBar.displayTime;
		signal.closedAt = closedAt;
		signal.tradeNotes = tradeNotes;
		signal.openedTime = entryBar.marketTime;
		signal.closedTime = closedTime;
		signal.failureExitPrice = roundToTwoDecimals(failureExitPrice);
		signal.bars = bars;
		signal.entryIndex = entryIndex;
		signal.coordinationScore = signalExecutionScore(signal);
		if (!isRecentVolatilityAllowed(signal)) {
			return null;
		}
		return signal;
	}

	private static TradeRecord createTrade(
		Signal signal,
		StrategyConfig config,
		double capital,
		double availableBuyingPower,
		double perTradeBuyingPower,
		double takeProfit,
		double lossLimit
	) {
		if (signal == null || config == null) {
			return null;
		}

		double perShareRisk = roundToTwoDecimals(Math.abs(signal.entryPrice - signal.stopPrice));
		if (perShareRisk <= 0.0) {
			return null;
		}

		double riskBudget = roundToTwoDecimals(capital * (config.riskPerTradePct / 100.0));
		double qtyByRisk = Math.floor(riskBudget / perShareRisk);
		double buyingPowerCapital = Math.max(0.0, availableBuyingPower);
		double buyingPowerCap = perTradeBuyingPower > 0.0 ? Math.min(buyingPowerCapital, perTradeBuyingPower) : buyingPowerCapital;
		double maxAffordableQty = Math.floor(buyingPowerCap / Math.max(1.0, signal.entryPrice));
		double qty = Math.floor(Math.min(qtyByRisk, maxAffordableQty));

		if (qty < 1.0) {
			return null;
		}

		double effectiveStopPrice = signal.stopPrice;
		double effectiveTargetPrice = signal.targetPrice;
		String guardrailNote = "";
		if (takeProfit > 0.0) {
			double takeProfitPrice = exitPriceForPnl(signal.side, signal.entryPrice, qty, takeProfit);
			if ("SHORT".equals(signal.side)) {
				effectiveTargetPrice = roundToTwoDecimals(Math.max(signal.targetPrice, takeProfitPrice));
			} else {
				effectiveTargetPrice = roundToTwoDecimals(Math.min(signal.targetPrice, takeProfitPrice));
			}
			if (effectiveTargetPrice != signal.targetPrice) {
				guardrailNote = appendGuardrail(guardrailNote, "Emergency take profit armed at $" + roundToTwoDecimals(takeProfit));
			}
		}
		if (lossLimit > 0.0) {
			double lossPrice = exitPriceForPnl(signal.side, signal.entryPrice, qty, -lossLimit);
			if ("SHORT".equals(signal.side)) {
				effectiveStopPrice = roundToTwoDecimals(Math.min(signal.stopPrice, lossPrice));
			} else {
				effectiveStopPrice = roundToTwoDecimals(Math.max(signal.stopPrice, lossPrice));
			}
			if (effectiveStopPrice != signal.stopPrice) {
				guardrailNote = appendGuardrail(guardrailNote, "Emergency loss limit armed at $" + roundToTwoDecimals(lossLimit));
			}
		}

		TradeExit exit = resolveTradeExit(signal, effectiveStopPrice, effectiveTargetPrice);
		double pnl = "SHORT".equals(signal.side)
			? roundToTwoDecimals((signal.entryPrice - exit.exitPrice) * qty)
			: roundToTwoDecimals((exit.exitPrice - signal.entryPrice) * qty);

		TradeRecord trade = new TradeRecord();
		trade.strategyCode = signal.strategyCode;
		trade.side = signal.side;
		trade.qty = qty;
		trade.entryPrice = signal.entryPrice;
		trade.exitPrice = exit.exitPrice;
		trade.openedAt = signal.openedAt;
		trade.closedAt = exit.closedAt;
		trade.tradeNotes = buildExecutionNotes(
			signal,
			exit,
			qty,
			effectiveStopPrice,
			effectiveTargetPrice,
			guardrailNote
		);
		trade.pnl = pnl;
		trade.openedTime = signal.openedTime;
		trade.closedTime = exit.closedTime;
		return trade;
	}

	private static TradeExit resolveTradeExit(Signal signal, double effectiveStopPrice, double effectiveTargetPrice) {
		TradeExit exit = new TradeExit();
		exit.exitPrice = signal.exitPrice;
		exit.closedAt = signal.closedAt;
		exit.closedTime = signal.closedTime;
		exit.reason = "Session close exit";

		if (signal.bars == null || signal.bars.isEmpty()) {
			return exit;
		}

		for (int index = signal.entryIndex + 1; index < signal.bars.size(); index++) {
			AlpacaManager.CachedBar bar = signal.bars.get(index);
			if ("LONG".equals(signal.side)) {
				if (bar.low <= effectiveStopPrice && bar.high >= effectiveTargetPrice) {
					exit.exitPrice = roundToTwoDecimals(effectiveStopPrice);
					exit.closedAt = bar.displayTime;
					exit.closedTime = bar.marketTime;
					exit.reason = "Stop and target both touched; stop assumed first";
					return exit;
				}
				if (bar.low <= effectiveStopPrice) {
					exit.exitPrice = roundToTwoDecimals(effectiveStopPrice);
					exit.closedAt = bar.displayTime;
					exit.closedTime = bar.marketTime;
					exit.reason = "Stop loss hit";
					return exit;
				}
				if (bar.high >= effectiveTargetPrice) {
					exit.exitPrice = roundToTwoDecimals(effectiveTargetPrice);
					exit.closedAt = bar.displayTime;
					exit.closedTime = bar.marketTime;
					exit.reason = "Target reached";
					return exit;
				}
			} else {
				if (bar.high >= effectiveStopPrice && bar.low <= effectiveTargetPrice) {
					exit.exitPrice = roundToTwoDecimals(effectiveStopPrice);
					exit.closedAt = bar.displayTime;
					exit.closedTime = bar.marketTime;
					exit.reason = "Stop and target both touched; stop assumed first";
					return exit;
				}
				if (bar.high >= effectiveStopPrice) {
					exit.exitPrice = roundToTwoDecimals(effectiveStopPrice);
					exit.closedAt = bar.displayTime;
					exit.closedTime = bar.marketTime;
					exit.reason = "Stop loss hit";
					return exit;
				}
				if (bar.low <= effectiveTargetPrice) {
					exit.exitPrice = roundToTwoDecimals(effectiveTargetPrice);
					exit.closedAt = bar.displayTime;
					exit.closedTime = bar.marketTime;
					exit.reason = "Target reached";
					return exit;
				}
			}
		}

		return exit;
	}

	private static void applyEmergencyExitLimits(Signal signal, TradeRecord trade, double takeProfit, double lossLimit) {
		if (signal == null || trade == null || trade.qty <= 0.0) {
			return;
		}

		if (takeProfit > 0.0 && trade.pnl > takeProfit) {
			double cappedPnl = roundToTwoDecimals(takeProfit);
			double emergencyExitPrice = exitPriceForPnl(trade.side, trade.entryPrice, trade.qty, cappedPnl);
			applyEmergencyExit(signal, trade, emergencyExitPrice, cappedPnl);
			trade.tradeNotes = appendGuardrail(trade.tradeNotes, "Emergency take profit hit at $" + roundToTwoDecimals(takeProfit));
			return;
		}

		if (lossLimit > 0.0 && trade.pnl < -lossLimit) {
			double cappedPnl = roundToTwoDecimals(-lossLimit);
			double emergencyExitPrice = exitPriceForPnl(trade.side, trade.entryPrice, trade.qty, cappedPnl);
			applyEmergencyExit(signal, trade, emergencyExitPrice, cappedPnl);
			trade.tradeNotes = appendGuardrail(trade.tradeNotes, "Emergency loss limit hit at $" + roundToTwoDecimals(lossLimit));
		}
	}

	private static void applyEmergencyExit(Signal signal, TradeRecord trade, double exitPrice, double pnl) {
		trade.exitPrice = roundToTwoDecimals(exitPrice);
		trade.pnl = roundToTwoDecimals(pnl);

		if (signal.bars == null || signal.bars.isEmpty()) {
			return;
		}

		for (int index = signal.entryIndex + 1; index < signal.bars.size(); index++) {
			AlpacaManager.CachedBar bar = signal.bars.get(index);
			if (bar.marketTime.isAfter(trade.closedTime)) {
				break;
			}

			if ("SHORT".equals(trade.side)) {
				boolean reachedExit = exitPrice <= trade.entryPrice
					? bar.low <= exitPrice
					: bar.high >= exitPrice;
				if (reachedExit) {
					trade.closedAt = bar.displayTime;
					trade.closedTime = bar.marketTime;
					return;
				}
			} else {
				boolean reachedExit = exitPrice >= trade.entryPrice
					? bar.high >= exitPrice
					: bar.low <= exitPrice;
				if (reachedExit) {
					trade.closedAt = bar.displayTime;
					trade.closedTime = bar.marketTime;
					return;
				}
			}
		}
	}

	private static double exitPriceForPnl(String side, double entryPrice, double qty, double pnl) {
		if (qty <= 0.0) {
			return roundToTwoDecimals(entryPrice);
		}

		if ("SHORT".equals(side)) {
			return roundToTwoDecimals(entryPrice - (pnl / qty));
		}

		return roundToTwoDecimals(entryPrice + (pnl / qty));
	}

	private static String buildExecutionNotes(
		Signal signal,
		TradeExit exit,
		double qty,
		double effectiveStopPrice,
		double effectiveTargetPrice,
		String guardrailNote
	) {
		String notes = signal.tradeNotes;
		if (guardrailNote != null && !guardrailNote.trim().isEmpty()) {
			notes = appendGuardrail(notes, guardrailNote);
		}

		TradeExcursion excursion = calculateTradeExcursion(signal, exit, qty);
		String exitReason = exit.reason == null || exit.reason.trim().isEmpty() ? "Exit resolved" : exit.reason;
		String diagnostics = "Exit: " + exitReason
			+ ". Stop $" + roundToTwoDecimals(effectiveStopPrice)
			+ ", target $" + roundToTwoDecimals(effectiveTargetPrice)
			+ ", MFE $" + roundToTwoDecimals(excursion.maxFavorablePnl)
			+ ", MAE $" + roundToTwoDecimals(excursion.maxAdversePnl);
		return appendGuardrail(notes, diagnostics);
	}

	private static TradeExcursion calculateTradeExcursion(Signal signal, TradeExit exit, double qty) {
		TradeExcursion excursion = new TradeExcursion();
		if (signal == null || exit == null || signal.bars == null || signal.bars.isEmpty() || qty <= 0.0) {
			return excursion;
		}

		double bestFavorable = 0.0;
		double worstAdverse = 0.0;
		for (int index = signal.entryIndex + 1; index < signal.bars.size(); index++) {
			AlpacaManager.CachedBar bar = signal.bars.get(index);
			if (exit.closedTime != null && bar.marketTime.isAfter(exit.closedTime)) {
				break;
			}

			double favorablePnl;
			double adversePnl;
			if ("SHORT".equals(signal.side)) {
				favorablePnl = (signal.entryPrice - bar.low) * qty;
				adversePnl = (signal.entryPrice - bar.high) * qty;
			} else {
				favorablePnl = (bar.high - signal.entryPrice) * qty;
				adversePnl = (bar.low - signal.entryPrice) * qty;
			}

			bestFavorable = Math.max(bestFavorable, favorablePnl);
			worstAdverse = Math.min(worstAdverse, adversePnl);
		}

		excursion.maxFavorablePnl = roundToTwoDecimals(bestFavorable);
		excursion.maxAdversePnl = roundToTwoDecimals(worstAdverse);
		return excursion;
	}

	private static String appendGuardrail(String tradeNotes, String guardrailNote) {
		String note = guardrailNote.endsWith(".") ? guardrailNote : guardrailNote + ".";
		if (tradeNotes == null || tradeNotes.trim().isEmpty()) {
			return note;
		}
		return tradeNotes + " " + note;
	}

	private static int findRetestEntryIndex(
		List<AlpacaManager.CachedBar> dayOneMinuteBars,
		LocalTime afterTime,
		LocalTime cutoffTime,
		String side,
		double zoneLow,
		double zoneHigh,
		double entryBufferPct
	) {
		double longTrigger = zoneHigh + percentOfPrice(zoneHigh, entryBufferPct);
		double shortTrigger = zoneLow - percentOfPrice(zoneLow, entryBufferPct);

		for (int index = 0; index < dayOneMinuteBars.size(); index++) {
			AlpacaManager.CachedBar bar = dayOneMinuteBars.get(index);
			if (!bar.marketTime.isAfter(afterTime)) {
				continue;
			}
			if (bar.marketTime.isAfter(cutoffTime)) {
				break;
			}

			if (
				"LONG".equals(side)
				&& bar.low <= zoneHigh
				&& bar.close >= longTrigger
				&& bar.close >= bar.open
				&& closePosition(bar, "LONG") >= MIN_RECLAIM_CLOSE_POSITION
			) {
				return index;
			}
			if (
				"SHORT".equals(side)
				&& bar.high >= zoneLow
				&& bar.close <= shortTrigger
				&& bar.close <= bar.open
				&& closePosition(bar, "SHORT") >= MIN_RECLAIM_CLOSE_POSITION
			) {
				return index;
			}
		}

		return -1;
	}

	private static boolean isIfvgImpulseBar(
		AlpacaManager.CachedBar firstBar,
		AlpacaManager.CachedBar impulseBar,
		List<AlpacaManager.CachedBar> signalBars,
		int index,
		String side
	) {
		if (firstBar == null || impulseBar == null) {
			return false;
		}

		double impulseRange = roundToTwoDecimals(impulseBar.high - impulseBar.low);
		if (impulseRange <= 0.0) {
			return false;
		}

		double impulseBody = roundToTwoDecimals(Math.abs(impulseBar.close - impulseBar.open));
		if ((impulseBody / impulseRange) < MIN_IFVG_IMPULSE_BODY_RATIO) {
			return false;
		}

		double recentAverageRange = averageRange(signalBars, Math.max(0, index - 4), index - 2);
		if (recentAverageRange > 0.0 && impulseRange < (recentAverageRange * MIN_IFVG_IMPULSE_RANGE_RATIO)) {
			return false;
		}

		if ("LONG".equals(side)) {
			return impulseBar.close < impulseBar.open && impulseBar.close < firstBar.low;
		}

		return impulseBar.close > impulseBar.open && impulseBar.close > firstBar.high;
	}

	private static Signal earliestSignal(Signal firstSignal, Signal secondSignal) {
		if (firstSignal == null) {
			return secondSignal;
		}
		if (secondSignal == null) {
			return firstSignal;
		}
		return firstSignal.openedTime.isBefore(secondSignal.openedTime) ? firstSignal : secondSignal;
	}

	private static List<Signal> dedupeSignals(List<Signal> signals) {
		List<Signal> dedupedSignals = new ArrayList<Signal>();
		for (Signal signal : signals) {
			addSignalIfUnique(dedupedSignals, signal);
		}
		return dedupedSignals;
	}

	private static void addSignalIfUnique(List<Signal> signals, Signal candidate) {
		if (candidate == null) {
			return;
		}

		for (Signal existingSignal : signals) {
			if (
				existingSignal.strategyCode.equals(candidate.strategyCode)
				&& existingSignal.side.equals(candidate.side)
				&& existingSignal.openedAt.equals(candidate.openedAt)
			) {
				return;
			}
		}

		signals.add(candidate);
	}

	private static LiveSignalSnapshot toLiveSignalSnapshot(Signal signal) {
		LiveSignalSnapshot snapshot = new LiveSignalSnapshot();
		snapshot.strategyCode = signal.strategyCode;
		snapshot.strategyName = strategyNameForCode(signal.strategyCode);
		snapshot.side = signal.side;
		snapshot.entryPrice = signal.entryPrice;
		snapshot.stopPrice = signal.stopPrice;
		snapshot.targetPrice = signal.targetPrice;
		snapshot.openedAt = signal.openedAt;
		snapshot.closedAt = signal.closedAt;
		snapshot.tradeNotes = signal.tradeNotes;
		snapshot.openedTime = signal.openedTime;
		snapshot.closedTime = signal.closedTime;
		snapshot.coordinationScore = signal.coordinationScore;
		return snapshot;
	}

	private static Signal buildVwapPullbackSignal(
		List<AlpacaManager.CachedBar> dayBars,
		int entryIndex,
		String side,
		Map<LocalTime, Double> vwapByTime,
		double[] ema9,
		double[] ema20,
		StrategyConfig config,
		Bias bias
	) {
		if (entryIndex <= 0 || entryIndex >= dayBars.size()) {
			return null;
		}

		AlpacaManager.CachedBar entryBar = dayBars.get(entryIndex);
		AlpacaManager.CachedBar previousBar = dayBars.get(entryIndex - 1);
		double vwap = vwapAtTime(vwapByTime, entryBar.marketTime);
		if (vwap <= 0.0) {
			return null;
		}

		double minVwapDistance = percentOfPrice(vwap, config.minimumGapPct);
		double volumeAverage = averageVolume(dayBars, Math.max(0, entryIndex - 20), entryIndex - 1);
		if (volumeAverage > 0.0 && entryBar.volume < volumeAverage * MIN_VWAP_CONFIRM_VOLUME_RATIO) {
			return null;
		}
		if (!hasRecentVwapPullback(dayBars, entryIndex, side, vwapByTime, config)) {
			return null;
		}

		int swingStartIndex = Math.max(0, entryIndex - Math.max(1, config.reclaimWindowBars));
		if ("LONG".equals(side)) {
			double reclaimBuffer = percentOfPrice(previousBar.high, config.entryBufferPct);
			if (
				entryBar.close <= vwap + minVwapDistance
				|| ema9[entryIndex] <= ema20[entryIndex]
				|| entryBar.close <= previousBar.high + reclaimBuffer
				|| entryBar.close < entryBar.open
				|| closePosition(entryBar, "LONG") < MIN_RECLAIM_CLOSE_POSITION
			) {
				return null;
			}

			double stopAnchor = Math.min(lowestLow(dayBars, swingStartIndex, entryIndex), vwap);
			double stopPrice = roundToTwoDecimals(stopAnchor - percentOfPrice(stopAnchor, config.stopBufferPct));
			if (entryBar.close <= stopPrice) {
				return null;
			}

			double targetPrice = roundToTwoDecimals(entryBar.close + ((entryBar.close - stopPrice) * config.rewardToRiskRatio));
			return simulateSignal(
				VWAP_CODE,
				"LONG",
				dayBars,
				entryIndex,
				entryBar.close,
				stopPrice,
				targetPrice,
				vwap,
				buildVwapNotes("LONG", config, vwap, bias)
			);
		}

		double reclaimBuffer = percentOfPrice(previousBar.low, config.entryBufferPct);
		if (
			entryBar.close >= vwap - minVwapDistance
			|| ema9[entryIndex] >= ema20[entryIndex]
			|| entryBar.close >= previousBar.low - reclaimBuffer
			|| entryBar.close > entryBar.open
			|| closePosition(entryBar, "SHORT") < MIN_RECLAIM_CLOSE_POSITION
		) {
			return null;
		}

		double stopAnchor = Math.max(highestHigh(dayBars, swingStartIndex, entryIndex), vwap);
		double stopPrice = roundToTwoDecimals(stopAnchor + percentOfPrice(stopAnchor, config.stopBufferPct));
		if (entryBar.close >= stopPrice) {
			return null;
		}

		double targetPrice = roundToTwoDecimals(entryBar.close - ((stopPrice - entryBar.close) * config.rewardToRiskRatio));
		return simulateSignal(
			VWAP_CODE,
			"SHORT",
			dayBars,
			entryIndex,
			entryBar.close,
			stopPrice,
			targetPrice,
			vwap,
			buildVwapNotes("SHORT", config, vwap, bias)
		);
	}

	private static Signal buildVwapMeanReversionSignal(
		List<AlpacaManager.CachedBar> dayBars,
		int entryIndex,
		String side,
		Map<LocalTime, Double> vwapByTime,
		double[] rsi14,
		StrategyConfig config,
		Bias bias
	) {
		if (entryIndex <= 0 || entryIndex >= dayBars.size()) {
			return null;
		}

		AlpacaManager.CachedBar entryBar = dayBars.get(entryIndex);
		AlpacaManager.CachedBar previousBar = dayBars.get(entryIndex - 1);
		double vwap = vwapAtTime(vwapByTime, entryBar.marketTime);
		if (vwap <= 0.0) {
			return null;
		}

		if (!hadRecentMeanReversionExhaustion(dayBars, entryIndex, side, vwapByTime, rsi14, config)) {
			return null;
		}

		int swingStartIndex = Math.max(0, entryIndex - Math.max(2, config.reclaimWindowBars));
		if ("LONG".equals(side)) {
			if (
				entryBar.close <= previousBar.high + percentOfPrice(previousBar.high, config.entryBufferPct)
				|| entryBar.close <= entryBar.open
				|| closePosition(entryBar, "LONG") < MIN_MRVWAP_REVERSAL_CLOSE_POSITION
				|| entryBar.close >= vwap
			) {
				return null;
			}

			double stopAnchor = lowestLow(dayBars, swingStartIndex, entryIndex);
			double stopPrice = roundToTwoDecimals(stopAnchor - percentOfPrice(stopAnchor, config.stopBufferPct));
			if (entryBar.close <= stopPrice) {
				return null;
			}
			double targetPrice = roundToTwoDecimals(entryBar.close + ((entryBar.close - stopPrice) * config.rewardToRiskRatio));
			if (targetPrice <= entryBar.close) {
				return null;
			}
			return simulateSignal(
				MRVWAP_CODE,
				"LONG",
				dayBars,
				entryIndex,
				entryBar.close,
				stopPrice,
				targetPrice,
				0.0,
				buildVwapMeanReversionNotes("LONG", config, vwap, rsi14[entryIndex], bias)
			);
		}

		if (
			entryBar.close >= previousBar.low - percentOfPrice(previousBar.low, config.entryBufferPct)
			|| entryBar.close >= entryBar.open
			|| closePosition(entryBar, "SHORT") < MIN_MRVWAP_REVERSAL_CLOSE_POSITION
			|| entryBar.close <= vwap
		) {
			return null;
		}

		double stopAnchor = highestHigh(dayBars, swingStartIndex, entryIndex);
		double stopPrice = roundToTwoDecimals(stopAnchor + percentOfPrice(stopAnchor, config.stopBufferPct));
		if (entryBar.close >= stopPrice) {
			return null;
		}
		double targetPrice = roundToTwoDecimals(entryBar.close - ((stopPrice - entryBar.close) * config.rewardToRiskRatio));
		if (targetPrice >= entryBar.close) {
			return null;
		}
		return simulateSignal(
			MRVWAP_CODE,
			"SHORT",
			dayBars,
			entryIndex,
			entryBar.close,
			stopPrice,
			targetPrice,
			0.0,
			buildVwapMeanReversionNotes("SHORT", config, vwap, rsi14[entryIndex], bias)
		);
	}

	private static Signal buildGapGoSignal(
		List<AlpacaManager.CachedBar> dayBars,
		int entryIndex,
		String side,
		double previousClose,
		double gapPct,
		double rangeHigh,
		double rangeLow,
		double rangeSize,
		double openingRangeAverageVolume,
		StrategyConfig config,
		Bias bias
	) {
		if (entryIndex < 0 || entryIndex >= dayBars.size() || rangeSize <= 0.0) {
			return null;
		}

		AlpacaManager.CachedBar entryBar = dayBars.get(entryIndex);
		if (openingRangeAverageVolume > 0.0 && entryBar.volume < openingRangeAverageVolume * MIN_GAPGO_BREAKOUT_VOLUME_RATIO) {
			return null;
		}

		if ("LONG".equals(side)) {
			double breakoutBuffer = percentOfPrice(rangeHigh, config.breakoutBufferPct);
			double extension = roundToTwoDecimals(entryBar.close - rangeHigh);
			if (
				entryBar.close <= rangeHigh + breakoutBuffer
				|| entryBar.close < entryBar.open
				|| closePosition(entryBar, "LONG") < MIN_BREAKOUT_CLOSE_POSITION
				|| extension <= 0.0
				|| extension > (rangeSize * MAX_GAPGO_EXTENSION_RANGE_MULTIPLE)
			) {
				return null;
			}

			double stopPrice = roundToTwoDecimals(rangeLow - percentOfPrice(rangeLow, config.stopBufferPct));
			if (entryBar.close <= stopPrice) {
				return null;
			}

			double targetPrice = roundToTwoDecimals(entryBar.close + ((entryBar.close - stopPrice) * config.rewardToRiskRatio));
			return simulateSignal(
				GAPGO_CODE,
				"LONG",
				dayBars,
				entryIndex,
				entryBar.close,
				stopPrice,
				targetPrice,
				rangeHigh,
				buildGapGoNotes("LONG", config, previousClose, gapPct, rangeHigh, rangeLow, bias)
			);
		}

		double breakoutBuffer = percentOfPrice(rangeLow, config.breakoutBufferPct);
		double extension = roundToTwoDecimals(rangeLow - entryBar.close);
		if (
			entryBar.close >= rangeLow - breakoutBuffer
			|| entryBar.close > entryBar.open
			|| closePosition(entryBar, "SHORT") < MIN_BREAKOUT_CLOSE_POSITION
			|| extension <= 0.0
			|| extension > (rangeSize * MAX_GAPGO_EXTENSION_RANGE_MULTIPLE)
		) {
			return null;
		}

		double stopPrice = roundToTwoDecimals(rangeHigh + percentOfPrice(rangeHigh, config.stopBufferPct));
		if (entryBar.close >= stopPrice) {
			return null;
		}

		double targetPrice = roundToTwoDecimals(entryBar.close - ((stopPrice - entryBar.close) * config.rewardToRiskRatio));
		return simulateSignal(
			GAPGO_CODE,
			"SHORT",
			dayBars,
			entryIndex,
			entryBar.close,
			stopPrice,
			targetPrice,
			rangeLow,
			buildGapGoNotes("SHORT", config, previousClose, gapPct, rangeHigh, rangeLow, bias)
		);
	}

	private static Signal buildOrbRetestSignal(
		List<AlpacaManager.CachedBar> dayBars,
		int breakoutIndex,
		String side,
		double rangeHigh,
		double rangeLow,
		StrategyConfig config,
		Bias bias
	) {
		int retestLimit = Math.min(dayBars.size() - 1, breakoutIndex + Math.max(1, config.reclaimWindowBars));
		double breakoutLevel = "LONG".equals(side) ? rangeHigh : rangeLow;
		double retestTrigger = percentOfPrice(breakoutLevel, config.entryBufferPct);
		double retestAllowance = percentOfPrice(breakoutLevel, config.breakoutBufferPct);
		double maxRetestPenetration = roundToTwoDecimals((rangeHigh - rangeLow) * MAX_ORB_RETEST_RANGE_PENETRATION_RATIO);
		double failureExitPrice = roundToTwoDecimals((rangeHigh + rangeLow) / 2.0);
		AlpacaManager.CachedBar breakoutBar = dayBars.get(breakoutIndex);

		for (int index = breakoutIndex + 1; index <= retestLimit; index++) {
			AlpacaManager.CachedBar retestBar = dayBars.get(index);
			if (retestBar.marketTime.isAfter(ORB_ENTRY_CUTOFF)) {
				break;
			}

			if ("LONG".equals(side)) {
				if (retestBar.low > rangeHigh + retestAllowance) {
					continue;
				}
				if (retestBar.low < rangeHigh - maxRetestPenetration) {
					continue;
				}
				if (retestBar.close < rangeHigh + retestTrigger) {
					continue;
				}
				if (retestBar.close < retestBar.open || closePosition(retestBar, "LONG") < MIN_RECLAIM_CLOSE_POSITION) {
					continue;
				}

				double stopAnchor = Math.min(rangeLow, retestBar.low);
				double stopPrice = roundToTwoDecimals(stopAnchor - percentOfPrice(stopAnchor, config.stopBufferPct));
				if (retestBar.close <= stopPrice) {
					continue;
				}

				double targetPrice = roundToTwoDecimals(retestBar.close + ((retestBar.close - stopPrice) * config.rewardToRiskRatio));
				return simulateSignal(
					ORB_CODE,
					"LONG",
					dayBars,
					index,
					retestBar.close,
					stopPrice,
					targetPrice,
					failureExitPrice,
					buildOrbNotes("LONG", config, rangeHigh, rangeLow, bias, "retest")
				);
			}

			if (retestBar.high < rangeLow - retestAllowance) {
				continue;
			}
			if (retestBar.high > rangeLow + maxRetestPenetration) {
				continue;
			}
			if (retestBar.close > rangeLow - retestTrigger) {
				continue;
			}
			if (retestBar.close > retestBar.open || closePosition(retestBar, "SHORT") < MIN_RECLAIM_CLOSE_POSITION) {
				continue;
			}

			double stopAnchor = Math.max(rangeHigh, retestBar.high);
			double stopPrice = roundToTwoDecimals(stopAnchor + percentOfPrice(stopAnchor, config.stopBufferPct));
			if (retestBar.close >= stopPrice) {
				continue;
			}

			double targetPrice = roundToTwoDecimals(retestBar.close - ((stopPrice - retestBar.close) * config.rewardToRiskRatio));
			return simulateSignal(
				ORB_CODE,
				"SHORT",
				dayBars,
				index,
				retestBar.close,
				stopPrice,
				targetPrice,
				failureExitPrice,
				buildOrbNotes("SHORT", config, rangeHigh, rangeLow, bias, "retest")
			);
		}

		double breakoutStopPrice;
		double breakoutTargetPrice;

		if ("LONG".equals(side)) {
			breakoutStopPrice = roundToTwoDecimals(rangeLow - percentOfPrice(rangeLow, config.stopBufferPct));
			if (breakoutBar.close <= breakoutStopPrice) {
				return null;
			}
			breakoutTargetPrice = roundToTwoDecimals(
				breakoutBar.close + ((breakoutBar.close - breakoutStopPrice) * config.rewardToRiskRatio)
			);
			return simulateSignal(
				ORB_CODE,
				"LONG",
				dayBars,
				breakoutIndex,
				breakoutBar.close,
				breakoutStopPrice,
				breakoutTargetPrice,
				failureExitPrice,
				buildOrbNotes("LONG", config, rangeHigh, rangeLow, bias, "breakout")
			);
		}

		breakoutStopPrice = roundToTwoDecimals(rangeHigh + percentOfPrice(rangeHigh, config.stopBufferPct));
		if (breakoutBar.close >= breakoutStopPrice) {
			return null;
		}
		breakoutTargetPrice = roundToTwoDecimals(
			breakoutBar.close - ((breakoutStopPrice - breakoutBar.close) * config.rewardToRiskRatio)
		);
		return simulateSignal(
			ORB_CODE,
			"SHORT",
			dayBars,
			breakoutIndex,
			breakoutBar.close,
			breakoutStopPrice,
			breakoutTargetPrice,
			failureExitPrice,
			buildOrbNotes("SHORT", config, rangeHigh, rangeLow, bias, "breakout")
		);
	}

	private static Signal buildOrbOpeningDriveSignal(
		List<AlpacaManager.CachedBar> dayBars,
		String side,
		double rangeHigh,
		double rangeLow,
		double rangeSize,
		double openingRangeAverageVolume,
		StrategyConfig config,
		Bias bias
	) {
		if (dayBars == null || dayBars.isEmpty() || rangeSize <= 0.0) {
			return null;
		}

		int driveIndex = -1;
		for (int index = config.orbWindowMinutes; index < dayBars.size(); index++) {
			AlpacaManager.CachedBar bar = dayBars.get(index);
			if (bar.marketTime.equals(ORB_OPENING_DRIVE_TIME)) {
				driveIndex = index;
				break;
			}
			if (bar.marketTime.isAfter(ORB_OPENING_DRIVE_TIME)) {
				break;
			}
		}

		if (driveIndex < 0) {
			return null;
		}

		AlpacaManager.CachedBar driveBar = dayBars.get(driveIndex);
		double sessionOpen = roundToTwoDecimals(dayBars.get(0).open);
		if (sessionOpen <= 0.0) {
			return null;
		}
		if (openingRangeAverageVolume > 0.0 && driveBar.volume < openingRangeAverageVolume * MIN_ORB_DRIVE_VOLUME_RATIO) {
			return null;
		}

		if ("LONG".equals(side)) {
			double extension = roundToTwoDecimals(driveBar.close - rangeHigh);
			if (
				driveBar.close <= sessionOpen
				|| driveBar.close <= rangeHigh
				|| driveBar.close < driveBar.open
				|| closePosition(driveBar, "LONG") < MIN_BREAKOUT_CLOSE_POSITION
				|| extension <= 0.0
				|| extension > (rangeSize * MAX_ORB_DRIVE_EXTENSION_RANGE_MULTIPLE)
			) {
				return null;
			}

			double stopPrice = roundToTwoDecimals(rangeHigh - percentOfPrice(rangeHigh, config.stopBufferPct));
			if (driveBar.close <= stopPrice) {
				return null;
			}

			double targetPrice = roundToTwoDecimals(driveBar.close + ((driveBar.close - stopPrice) * config.rewardToRiskRatio));
			return simulateSignal(
				ORB_CODE,
				"LONG",
				dayBars,
				driveIndex,
				driveBar.close,
				stopPrice,
				targetPrice,
				rangeHigh,
				buildOrbNotes("LONG", config, rangeHigh, rangeLow, bias, "drive")
			);
		}

		double extension = roundToTwoDecimals(rangeLow - driveBar.close);
		if (
			driveBar.close >= sessionOpen
			|| driveBar.close >= rangeLow
			|| driveBar.close > driveBar.open
			|| closePosition(driveBar, "SHORT") < MIN_BREAKOUT_CLOSE_POSITION
			|| extension <= 0.0
			|| extension > (rangeSize * MAX_ORB_DRIVE_EXTENSION_RANGE_MULTIPLE)
		) {
			return null;
		}

		double stopPrice = roundToTwoDecimals(rangeLow + percentOfPrice(rangeLow, config.stopBufferPct));
		if (driveBar.close >= stopPrice) {
			return null;
		}

		double targetPrice = roundToTwoDecimals(driveBar.close - ((stopPrice - driveBar.close) * config.rewardToRiskRatio));
		return simulateSignal(
			ORB_CODE,
			"SHORT",
			dayBars,
			driveIndex,
			driveBar.close,
			stopPrice,
			targetPrice,
			rangeLow,
			buildOrbNotes("SHORT", config, rangeHigh, rangeLow, bias, "drive")
		);
	}

	private static boolean isOrbBreakoutBar(
		AlpacaManager.CachedBar bar,
		String side,
		double breakoutLevel,
		double breakoutBuffer,
		double rangeSize,
		double openingRangeAverageVolume
	) {
		double barRange = roundToTwoDecimals(bar.high - bar.low);
		if (barRange <= 0.0 || rangeSize <= 0.0) {
			return false;
		}
		if (openingRangeAverageVolume > 0.0 && bar.volume < openingRangeAverageVolume * MIN_ORB_BREAKOUT_VOLUME_RATIO) {
			return false;
		}

		if ("LONG".equals(side)) {
			double bodySize = roundToTwoDecimals(bar.close - bar.open);
			double extension = roundToTwoDecimals(bar.close - breakoutLevel);
			return bodySize > 0.0
				&& bar.close > breakoutLevel + breakoutBuffer
				&& closePosition(bar, "LONG") >= MIN_BREAKOUT_CLOSE_POSITION
				&& (bodySize / barRange) >= MIN_BREAKOUT_BODY_RATIO
				&& extension <= (rangeSize * MAX_BREAKOUT_EXTENSION_RANGE_MULTIPLE);
		}

		double bodySize = roundToTwoDecimals(bar.open - bar.close);
		double extension = roundToTwoDecimals(breakoutLevel - bar.close);
		return bodySize > 0.0
			&& bar.close < breakoutLevel - breakoutBuffer
			&& closePosition(bar, "SHORT") >= MIN_BREAKOUT_CLOSE_POSITION
			&& (bodySize / barRange) >= MIN_BREAKOUT_BODY_RATIO
			&& extension <= (rangeSize * MAX_BREAKOUT_EXTENSION_RANGE_MULTIPLE);
	}

	private static LocalTime limitRetestCutoff(LocalTime startTime, String timeframe, int windowBars) {
		int timeframeMinutes = timeframeToMinutes(timeframe);
		int barsToUse = Math.max(1, windowBars);
		LocalTime cutoff = startTime.plusMinutes((long) timeframeMinutes * barsToUse);
		return cutoff.isAfter(IFVG_ENTRY_CUTOFF) ? IFVG_ENTRY_CUTOFF : cutoff;
	}

	private static int timeframeToMinutes(String timeframe) {
		if ("30Min".equals(timeframe)) {
			return 30;
		}
		if ("1Hour".equals(timeframe)) {
			return 60;
		}
		if ("5Min".equals(timeframe)) {
			return 5;
		}
		return 1;
	}

	private static double closePosition(AlpacaManager.CachedBar bar, String side) {
		double barRange = roundToTwoDecimals(bar.high - bar.low);
		if (barRange <= 0.0) {
			return 0.0;
		}

		if ("SHORT".equals(side)) {
			return roundToTwoDecimals((bar.high - bar.close) / barRange);
		}

		return roundToTwoDecimals((bar.close - bar.low) / barRange);
	}

	private static double averageVolume(List<AlpacaManager.CachedBar> bars, int startIndex, int endIndex) {
		if (bars == null || bars.isEmpty() || startIndex > endIndex) {
			return 0.0;
		}

		double totalVolume = 0.0;
		int count = 0;
		for (int index = Math.max(0, startIndex); index <= endIndex && index < bars.size(); index++) {
			double volume = bars.get(index).volume;
			if (volume <= 0.0) {
				continue;
			}
			totalVolume += volume;
			count++;
		}

		if (count == 0) {
			return 0.0;
		}

		return totalVolume / count;
	}

	private static double averageRange(List<AlpacaManager.CachedBar> bars, int startIndex, int endIndex) {
		if (bars == null || bars.isEmpty() || startIndex > endIndex) {
			return 0.0;
		}

		double totalRange = 0.0;
		int count = 0;
		for (int index = Math.max(0, startIndex); index <= endIndex && index < bars.size(); index++) {
			AlpacaManager.CachedBar bar = bars.get(index);
			double range = roundToTwoDecimals(bar.high - bar.low);
			if (range <= 0.0) {
				continue;
			}
			totalRange += range;
			count++;
		}

		if (count == 0) {
			return 0.0;
		}

		return totalRange / count;
	}

	private static Map<LocalTime, Double> buildSessionVwapMap(List<AlpacaManager.CachedBar> bars) {
		Map<LocalTime, Double> vwapByTime = new HashMap<LocalTime, Double>();
		if (bars == null || bars.isEmpty()) {
			return vwapByTime;
		}

		double cumulativePriceVolume = 0.0;
		double cumulativeVolume = 0.0;
		for (int index = 0; index < bars.size(); index++) {
			AlpacaManager.CachedBar bar = bars.get(index);
			double typicalPrice = bar.vwap > 0.0 ? bar.vwap : roundToTwoDecimals((bar.high + bar.low + bar.close) / 3.0);
			double volume = Math.max(0.0, bar.volume);
			cumulativePriceVolume += (typicalPrice * volume);
			cumulativeVolume += volume;
			if (cumulativeVolume > 0.0) {
				vwapByTime.put(bar.marketTime, roundToTwoDecimals(cumulativePriceVolume / cumulativeVolume));
			}
		}

		return vwapByTime;
	}

	private static double[] buildEmaValues(List<AlpacaManager.CachedBar> bars, int period) {
		double[] values = new double[bars == null ? 0 : bars.size()];
		if (bars == null || bars.isEmpty() || period <= 0) {
			return values;
		}

		double multiplier = 2.0 / (period + 1.0);
		double ema = bars.get(0).close;
		values[0] = ema;
		for (int index = 1; index < bars.size(); index++) {
			ema = ((bars.get(index).close - ema) * multiplier) + ema;
			values[index] = ema;
		}
		return values;
	}

	private static double[] buildRsiValues(List<AlpacaManager.CachedBar> bars, int period) {
		double[] values = new double[bars == null ? 0 : bars.size()];
		if (bars == null || bars.size() <= period || period <= 0) {
			return values;
		}

		for (int index = 0; index < bars.size(); index++) {
			if (index < period) {
				values[index] = 50.0;
				continue;
			}
			double gainTotal = 0.0;
			double lossTotal = 0.0;
			for (int cursor = index - period + 1; cursor <= index; cursor++) {
				double change = bars.get(cursor).close - bars.get(cursor - 1).close;
				if (change >= 0.0) {
					gainTotal += change;
				} else {
					lossTotal += Math.abs(change);
				}
			}
			double averageGain = gainTotal / period;
			double averageLoss = lossTotal / period;
			if (averageLoss == 0.0) {
				values[index] = averageGain == 0.0 ? 50.0 : 100.0;
			} else {
				double relativeStrength = averageGain / averageLoss;
				values[index] = 100.0 - (100.0 / (1.0 + relativeStrength));
			}
		}
		return values;
	}

	private static double vwapAtTime(Map<LocalTime, Double> vwapByTime, LocalTime marketTime) {
		if (vwapByTime == null || marketTime == null) {
			return 0.0;
		}
		Double value = vwapByTime.get(marketTime);
		return value == null ? 0.0 : value.doubleValue();
	}

	private static boolean hasRecentVwapPullback(
		List<AlpacaManager.CachedBar> bars,
		int entryIndex,
		String side,
		Map<LocalTime, Double> vwapByTime,
		StrategyConfig config
	) {
		int windowBars = Math.max(1, config.reclaimWindowBars);
		int startIndex = Math.max(0, entryIndex - windowBars);
		for (int index = startIndex; index < entryIndex; index++) {
			AlpacaManager.CachedBar bar = bars.get(index);
			double vwap = vwapAtTime(vwapByTime, bar.marketTime);
			if (vwap <= 0.0) {
				continue;
			}

			double pullbackBuffer = percentOfPrice(vwap, config.entryBufferPct);
			if ("LONG".equals(side) && bar.low <= vwap + pullbackBuffer && bar.close >= vwap - pullbackBuffer) {
				return true;
			}
			if ("SHORT".equals(side) && bar.high >= vwap - pullbackBuffer && bar.close <= vwap + pullbackBuffer) {
				return true;
			}
		}
		return false;
	}

	private static boolean hadRecentMeanReversionExhaustion(
		List<AlpacaManager.CachedBar> bars,
		int entryIndex,
		String side,
		Map<LocalTime, Double> vwapByTime,
		double[] rsi14,
		StrategyConfig config
	) {
		int windowBars = Math.max(2, config.reclaimWindowBars);
		int startIndex = Math.max(0, entryIndex - windowBars);
		for (int index = startIndex; index < entryIndex; index++) {
			AlpacaManager.CachedBar bar = bars.get(index);
			double vwap = vwapAtTime(vwapByTime, bar.marketTime);
			if (vwap <= 0.0) {
				continue;
			}
			double distancePct = Math.abs((bar.close - vwap) / vwap) * 100.0;
			if (distancePct < config.minimumGapPct) {
				continue;
			}
			double rsi = index >= 0 && index < rsi14.length ? rsi14[index] : 50.0;
			if ("LONG".equals(side) && bar.close < vwap && rsi <= MRVWAP_OVERSOLD_RSI) {
				return true;
			}
			if ("SHORT".equals(side) && bar.close > vwap && rsi >= MRVWAP_OVERBOUGHT_RSI) {
				return true;
			}
		}
		return false;
	}

	private static double lowestLow(List<AlpacaManager.CachedBar> bars, int startIndex, int endIndex) {
		if (bars == null || bars.isEmpty()) {
			return 0.0;
		}
		double low = Double.POSITIVE_INFINITY;
		for (int index = Math.max(0, startIndex); index <= endIndex && index < bars.size(); index++) {
			low = Math.min(low, bars.get(index).low);
		}
		return low == Double.POSITIVE_INFINITY ? 0.0 : roundToTwoDecimals(low);
	}

	private static double highestHigh(List<AlpacaManager.CachedBar> bars, int startIndex, int endIndex) {
		if (bars == null || bars.isEmpty()) {
			return 0.0;
		}
		double high = Double.NEGATIVE_INFINITY;
		for (int index = Math.max(0, startIndex); index <= endIndex && index < bars.size(); index++) {
			high = Math.max(high, bars.get(index).high);
		}
		return high == Double.NEGATIVE_INFINITY ? 0.0 : roundToTwoDecimals(high);
	}

	private static double previousCloseBefore(List<AlpacaManager.CachedBar> bars, LocalDate tradingDay) {
		if (bars == null || bars.isEmpty() || tradingDay == null) {
			return 0.0;
		}

		double previousClose = 0.0;
		for (int index = 0; index < bars.size(); index++) {
			AlpacaManager.CachedBar bar = bars.get(index);
			if (bar.marketDate.isBefore(tradingDay)) {
				previousClose = bar.close;
				continue;
			}
			if (bar.marketDate.isAfter(tradingDay)) {
				break;
			}
		}
		return roundToTwoDecimals(previousClose);
	}

	private static String buildOrbNotes(String side, StrategyConfig config, double rangeHigh, double rangeLow, Bias bias, String entryStyle) {
		String label = "retest".equals(entryStyle) ? "Retest" : ("drive".equals(entryStyle) ? "Drive" : "Breakout");
		return "ORB " + side.toLowerCase()
			+ ". " + config.orbWindowMinutes + "m range "
			+ "high " + roundToTwoDecimals(rangeHigh)
			+ ", low " + roundToTwoDecimals(rangeLow)
			+ ". " + label + " entry."
			+ " Bias " + bias.name().toLowerCase()
			+ " from " + config.trendTimeframe
			+ ". " + roundToTwoDecimals(config.rewardToRiskRatio) + ":1 target.";
	}

	private static String buildIfvgNotes(String side, StrategyConfig config, double gapLow, double gapHigh, Bias bias) {
		return "IFVG " + side.toLowerCase()
			+ ". Flipped gap " + roundToTwoDecimals(gapLow)
			+ " to " + roundToTwoDecimals(gapHigh)
			+ " on " + config.signalTimeframe
			+ ". Retest entry with " + bias.name().toLowerCase()
			+ " bias from " + config.trendTimeframe
			+ ". " + roundToTwoDecimals(config.rewardToRiskRatio) + ":1 target.";
	}

	private static String buildVwapNotes(String side, StrategyConfig config, double vwap, Bias bias) {
		return "VWAP pullback " + side.toLowerCase()
			+ ". Reclaimed session VWAP " + roundToTwoDecimals(vwap)
			+ " after a controlled pullback."
			+ " Bias " + bias.name().toLowerCase()
			+ " from " + config.trendTimeframe
			+ ". " + roundToTwoDecimals(config.rewardToRiskRatio) + ":1 target.";
	}

	private static String buildVwapMeanReversionNotes(String side, StrategyConfig config, double vwap, double rsi, Bias bias) {
		return "VWAP RSI mean reversion " + side.toLowerCase()
			+ ". Faded extension back toward VWAP " + roundToTwoDecimals(vwap)
			+ " after RSI " + roundToTwoDecimals(rsi)
			+ ". Bias " + bias.name().toLowerCase()
			+ " from " + config.trendTimeframe
			+ ". " + roundToTwoDecimals(config.rewardToRiskRatio) + ":1 risk cap.";
	}

	private static String buildGapGoNotes(
		String side,
		StrategyConfig config,
		double previousClose,
		double gapPct,
		double rangeHigh,
		double rangeLow,
		Bias bias
	) {
		return "Gap-and-go " + side.toLowerCase()
			+ ". Previous close " + roundToTwoDecimals(previousClose)
			+ ", opening gap " + roundToTwoDecimals(gapPct) + "%"
			+ ". " + config.orbWindowMinutes + "m range high " + roundToTwoDecimals(rangeHigh)
			+ ", low " + roundToTwoDecimals(rangeLow)
			+ ". Bias " + bias.name().toLowerCase()
			+ " from " + config.trendTimeframe
			+ ". " + roundToTwoDecimals(config.rewardToRiskRatio) + ":1 target.";
	}

	private static String strategyNameForCode(String strategyCode) {
		if (ORB_CODE.equals(strategyCode)) {
			return ORB_NAME;
		}
		if (IFVG_CODE.equals(strategyCode)) {
			return IFVG_NAME;
		}
		if (VWAP_CODE.equals(strategyCode)) {
			return VWAP_NAME;
		}
		if (MRVWAP_CODE.equals(strategyCode)) {
			return MRVWAP_NAME;
		}
		if (GAPGO_CODE.equals(strategyCode)) {
			return GAPGO_NAME;
		}
		return strategyCode == null ? "" : strategyCode;
	}

	private static String buildTimeframeSummary(StrategySettings settings) {
		List<String> timeframes = new ArrayList<String>();

		if (settings.orb != null && settings.orb.isEnabled) {
			addUnique(timeframes, settings.orb.timeframe);
			addUnique(timeframes, settings.orb.trendTimeframe);
		}

		if (settings.ifvg != null && settings.ifvg.isEnabled) {
			addUnique(timeframes, settings.ifvg.signalTimeframe);
			addUnique(timeframes, settings.ifvg.trendTimeframe);
		}

		if (settings.vwapPullback != null && settings.vwapPullback.isEnabled) {
			addUnique(timeframes, settings.vwapPullback.timeframe);
			addUnique(timeframes, settings.vwapPullback.trendTimeframe);
		}

		if (settings.vwapMeanReversion != null && settings.vwapMeanReversion.isEnabled) {
			addUnique(timeframes, settings.vwapMeanReversion.timeframe);
			addUnique(timeframes, settings.vwapMeanReversion.trendTimeframe);
		}

		if (settings.gapGo != null && settings.gapGo.isEnabled) {
			addUnique(timeframes, settings.gapGo.timeframe);
			addUnique(timeframes, settings.gapGo.trendTimeframe);
		}

		if (timeframes.isEmpty()) {
			return "Strategy Settings";
		}

		StringBuilder summary = new StringBuilder();
		for (int index = 0; index < timeframes.size(); index++) {
			if (index > 0) {
				summary.append(" / ");
			}
			summary.append(timeframes.get(index));
		}
		return summary.toString();
	}

	private static void addUnique(List<String> values, String value) {
		if (value == null || value.trim().isEmpty()) {
			return;
		}
		if (!values.contains(value)) {
			values.add(value);
		}
	}

	private static void ensureStrategyRow(Connection conn, StrategyConfig defaults) throws SQLException {
		String findSql = "SELECT strategyID FROM Strategies WHERE strategyCode = ? OR strategyName = ? LIMIT 1";
		try (PreparedStatement findStmt = conn.prepareStatement(findSql)) {
			findStmt.setString(1, defaults.strategyCode);
			findStmt.setString(2, defaults.strategyName);

			try (ResultSet rs = findStmt.executeQuery()) {
				if (rs.next()) {
					fillMissingDefaults(conn, rs.getInt("strategyID"), defaults);
					return;
				}
			}
		}

		String insertSql = "INSERT INTO Strategies (strategyName, description, timeframe, riskPerTradePct, takeProfitPct, stopLossPct, maxTradesPerDay, isEnabled, strategyCode, trendTimeframe, signalTimeframe, rewardToRiskRatio, requireTrendAlignment, orbWindowMinutes, breakoutBufferPct, minimumGapPct, reclaimWindowBars, entryBufferPct, stopBufferPct) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
			insertStmt.setString(1, defaults.strategyName);
			insertStmt.setString(2, defaults.description);
			insertStmt.setString(3, defaults.timeframe);
			insertStmt.setDouble(4, defaults.riskPerTradePct);
			insertStmt.setDouble(5, defaults.rewardToRiskRatio);
			insertStmt.setDouble(6, defaults.stopBufferPct);
			insertStmt.setInt(7, defaults.maxTradesPerDay);
			insertStmt.setInt(8, defaults.isEnabled ? 1 : 0);
			insertStmt.setString(9, defaults.strategyCode);
			insertStmt.setString(10, defaults.trendTimeframe);
			insertStmt.setString(11, defaults.signalTimeframe);
			insertStmt.setDouble(12, defaults.rewardToRiskRatio);
			insertStmt.setInt(13, defaults.requireTrendAlignment ? 1 : 0);
			insertStmt.setInt(14, defaults.orbWindowMinutes);
			insertStmt.setDouble(15, defaults.breakoutBufferPct);
			insertStmt.setDouble(16, defaults.minimumGapPct);
			insertStmt.setInt(17, defaults.reclaimWindowBars);
			insertStmt.setDouble(18, defaults.entryBufferPct);
			insertStmt.setDouble(19, defaults.stopBufferPct);
			insertStmt.executeUpdate();
		}
	}

	private static void fillMissingDefaults(Connection conn, int strategyId, StrategyConfig defaults) throws SQLException {
		String sql = "UPDATE Strategies SET "
			+ "strategyCode = CASE WHEN strategyCode IS NULL OR TRIM(strategyCode) = '' THEN ? ELSE strategyCode END, "
			+ "strategyName = CASE WHEN strategyName IS NULL OR TRIM(strategyName) = '' THEN ? ELSE strategyName END, "
			+ "description = CASE WHEN description IS NULL OR TRIM(description) = '' THEN ? ELSE description END, "
			+ "timeframe = CASE WHEN timeframe IS NULL OR TRIM(timeframe) = '' THEN ? ELSE timeframe END, "
			+ "riskPerTradePct = CASE WHEN riskPerTradePct IS NULL OR riskPerTradePct <= 0 THEN ? ELSE riskPerTradePct END, "
			+ "maxTradesPerDay = CASE WHEN maxTradesPerDay IS NULL OR maxTradesPerDay <= 0 THEN ? ELSE maxTradesPerDay END, "
			+ "isEnabled = CASE WHEN isEnabled IS NULL THEN ? ELSE isEnabled END, "
			+ "trendTimeframe = CASE WHEN trendTimeframe IS NULL OR TRIM(trendTimeframe) = '' THEN ? ELSE trendTimeframe END, "
			+ "signalTimeframe = CASE WHEN signalTimeframe IS NULL OR TRIM(signalTimeframe) = '' THEN ? ELSE signalTimeframe END, "
			+ "rewardToRiskRatio = CASE WHEN rewardToRiskRatio IS NULL OR rewardToRiskRatio <= 0 THEN ? ELSE rewardToRiskRatio END, "
			+ "requireTrendAlignment = CASE WHEN requireTrendAlignment IS NULL THEN ? ELSE requireTrendAlignment END, "
			+ "orbWindowMinutes = CASE WHEN orbWindowMinutes IS NULL OR orbWindowMinutes <= 0 THEN ? ELSE orbWindowMinutes END, "
			+ "breakoutBufferPct = CASE WHEN breakoutBufferPct IS NULL OR breakoutBufferPct < 0 THEN ? ELSE breakoutBufferPct END, "
			+ "minimumGapPct = CASE WHEN minimumGapPct IS NULL OR minimumGapPct <= 0 THEN ? ELSE minimumGapPct END, "
			+ "reclaimWindowBars = CASE WHEN reclaimWindowBars IS NULL OR reclaimWindowBars <= 0 THEN ? ELSE reclaimWindowBars END, "
			+ "entryBufferPct = CASE WHEN entryBufferPct IS NULL OR entryBufferPct < 0 THEN ? ELSE entryBufferPct END, "
			+ "stopBufferPct = CASE WHEN stopBufferPct IS NULL OR stopBufferPct < 0 THEN ? ELSE stopBufferPct END "
			+ "WHERE strategyID = ?";

		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, defaults.strategyCode);
			pstmt.setString(2, defaults.strategyName);
			pstmt.setString(3, defaults.description);
			pstmt.setString(4, defaults.timeframe);
			pstmt.setDouble(5, defaults.riskPerTradePct);
			pstmt.setInt(6, defaults.maxTradesPerDay);
			pstmt.setInt(7, defaults.isEnabled ? 1 : 0);
			pstmt.setString(8, defaults.trendTimeframe);
			pstmt.setString(9, defaults.signalTimeframe);
			pstmt.setDouble(10, defaults.rewardToRiskRatio);
			pstmt.setInt(11, defaults.requireTrendAlignment ? 1 : 0);
			pstmt.setInt(12, defaults.orbWindowMinutes);
			pstmt.setDouble(13, defaults.breakoutBufferPct);
			pstmt.setDouble(14, defaults.minimumGapPct);
			pstmt.setInt(15, defaults.reclaimWindowBars);
			pstmt.setDouble(16, defaults.entryBufferPct);
			pstmt.setDouble(17, defaults.stopBufferPct);
			pstmt.setInt(18, strategyId);
			pstmt.executeUpdate();
		}
	}

	private static void ensureColumnExists(Connection conn, String tableName, String columnName, String columnType) throws SQLException {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
			while (rs.next()) {
				if (columnName.equalsIgnoreCase(rs.getString("name"))) {
					return;
				}
			}
		}

		try (Statement stmt = conn.createStatement()) {
			stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType);
		}
	}

	private static String configToJson(StrategyConfig config) {
		return "{"
			+ "\"enabled\":" + config.isEnabled + ","
			+ "\"name\":" + jsonString(config.strategyName) + ","
			+ "\"description\":" + jsonString(config.description) + ","
			+ "\"timeframe\":" + jsonString(config.timeframe) + ","
			+ "\"riskPerTradePct\":" + roundToTwoDecimals(config.riskPerTradePct) + ","
			+ "\"maxTradesPerDay\":" + config.maxTradesPerDay + ","
			+ "\"trendTimeframe\":" + jsonString(config.trendTimeframe) + ","
			+ "\"signalTimeframe\":" + jsonString(config.signalTimeframe) + ","
			+ "\"rewardToRiskRatio\":" + roundToTwoDecimals(config.rewardToRiskRatio) + ","
			+ "\"requireTrendAlignment\":" + config.requireTrendAlignment + ","
			+ "\"orbWindowMinutes\":" + config.orbWindowMinutes + ","
			+ "\"breakoutBufferPct\":" + roundToTwoDecimals(config.breakoutBufferPct) + ","
			+ "\"minimumGapPct\":" + roundToTwoDecimals(config.minimumGapPct) + ","
			+ "\"reclaimWindowBars\":" + config.reclaimWindowBars + ","
			+ "\"entryBufferPct\":" + roundToTwoDecimals(config.entryBufferPct) + ","
			+ "\"stopBufferPct\":" + roundToTwoDecimals(config.stopBufferPct)
			+ "}";
	}

	private static String enabledStrategiesJson(StrategySettings settings) {
		StringBuilder json = new StringBuilder("[");
		if (settings.orb != null && settings.orb.isEnabled) {
			json.append(jsonString(settings.orb.strategyName));
		}
		if (settings.ifvg != null && settings.ifvg.isEnabled) {
			if (json.length() > 1) {
				json.append(",");
			}
			json.append(jsonString(settings.ifvg.strategyName));
		}
		if (settings.vwapPullback != null && settings.vwapPullback.isEnabled) {
			if (json.length() > 1) {
				json.append(",");
			}
			json.append(jsonString(settings.vwapPullback.strategyName));
		}
		if (settings.vwapMeanReversion != null && settings.vwapMeanReversion.isEnabled) {
			if (json.length() > 1) {
				json.append(",");
			}
			json.append(jsonString(settings.vwapMeanReversion.strategyName));
		}
		if (settings.gapGo != null && settings.gapGo.isEnabled) {
			if (json.length() > 1) {
				json.append(",");
			}
			json.append(jsonString(settings.gapGo.strategyName));
		}
		json.append("]");
		return json.toString();
	}

	private static String normalizeStrategyCode(String value) {
		if (value == null) {
			return "";
		}

		String normalized = value.trim().toUpperCase();
		if (normalized.contains("OPENING") || normalized.contains("ORB")) {
			return ORB_CODE;
		}
		if (normalized.contains("INVERSE") || normalized.contains("IFVG")) {
			return IFVG_CODE;
		}
		if (normalized.contains("MEAN") || normalized.contains("RSI") || normalized.contains(MRVWAP_CODE)) {
			return MRVWAP_CODE;
		}
		if (normalized.contains("VWAP")) {
			return VWAP_CODE;
		}
		if (normalized.contains("GAP") || normalized.contains("GO")) {
			return GAPGO_CODE;
		}
		return normalized;
	}

	private static String normalizeTimeframe(String value, String defaultValue) {
		if ("5Min".equalsIgnoreCase(value)) {
			return "5Min";
		}
		if ("30Min".equalsIgnoreCase(value)) {
			return "30Min";
		}
		if ("1Hour".equalsIgnoreCase(value)) {
			return "1Hour";
		}
		if ("1Min".equalsIgnoreCase(value)) {
			return "1Min";
		}
		return defaultValue;
	}

	private static String normalizeTrendTimeframe(String value, String defaultValue) {
		if ("1Hour".equalsIgnoreCase(value)) {
			return "1Hour";
		}
		if ("30Min".equalsIgnoreCase(value)) {
			return "30Min";
		}
		return defaultValue;
	}

	private static String normalizeSignalTimeframe(String value, String defaultValue) {
		if ("30Min".equalsIgnoreCase(value)) {
			return "30Min";
		}
		if ("5Min".equalsIgnoreCase(value)) {
			return "5Min";
		}
		if ("1Min".equalsIgnoreCase(value)) {
			return "1Min";
		}
		return defaultValue;
	}

	private static String readString(ResultSet rs, String columnName, String defaultValue) throws SQLException {
		String value = rs.getString(columnName);
		return nonBlank(value, defaultValue);
	}

	private static double readDouble(ResultSet rs, String columnName, double defaultValue) throws SQLException {
		Object value = rs.getObject(columnName);
		if (value == null) {
			return defaultValue;
		}
		return rs.getDouble(columnName);
	}

	private static int readInt(ResultSet rs, String columnName, int defaultValue) throws SQLException {
		Object value = rs.getObject(columnName);
		if (value == null) {
			return defaultValue;
		}
		return rs.getInt(columnName);
	}

	private static boolean readBoolean(ResultSet rs, String columnName, boolean defaultValue) throws SQLException {
		Object value = rs.getObject(columnName);
		if (value == null) {
			return defaultValue;
		}
		return rs.getInt(columnName) != 0;
	}

	private static String nonBlank(String value, String defaultValue) {
		return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
	}

	private static int boundedInt(int value, int defaultValue, int minValue, int maxValue) {
		int candidate = value <= 0 ? defaultValue : value;
		if (candidate < minValue) {
			return minValue;
		}
		if (candidate > maxValue) {
			return maxValue;
		}
		return candidate;
	}

	private static double boundedDouble(double value, double defaultValue, double minValue, double maxValue) {
		double candidate = Double.isNaN(value) || Double.isInfinite(value) ? defaultValue : value;
		if (candidate < minValue) {
			return minValue;
		}
		if (candidate > maxValue) {
			return maxValue;
		}
		return roundToTwoDecimals(candidate);
	}

	private static double percentOfPrice(double price, double pctValue) {
		return roundToTwoDecimals(price * (pctValue / 100.0));
	}

	private static double positiveOrDefault(double value, double defaultValue) {
		return value > 0.0 ? roundToTwoDecimals(value) : defaultValue;
	}

	private static double roundToTwoDecimals(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static String jsonString(String value) {
		String safeValue = value == null ? "" : value;
		safeValue = safeValue
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r");
		return "\"" + safeValue + "\"";
	}

	private static String safeString(String value) {
		return value == null ? "" : value;
	}

	private static int configuredInt(String environmentKey, String propertyKey, int defaultValue, int minValue, int maxValue) {
		String rawValue = configuredValue(environmentKey, propertyKey);
		if (rawValue == null || rawValue.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			int parsed = Integer.parseInt(rawValue.trim());
			return Math.max(minValue, Math.min(maxValue, parsed));
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private static double configuredDouble(String environmentKey, String propertyKey, double defaultValue, double minValue, double maxValue) {
		String rawValue = configuredValue(environmentKey, propertyKey);
		if (rawValue == null || rawValue.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			double parsed = Double.parseDouble(rawValue.trim());
			return Math.max(minValue, Math.min(maxValue, parsed));
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private static String configuredValue(String environmentKey, String propertyKey) {
		String propertyValue = System.getProperty(propertyKey);
		if (propertyValue != null && !propertyValue.trim().isEmpty()) {
			return propertyValue;
		}
		String environmentValue = System.getenv(environmentKey);
		if (environmentValue != null && !environmentValue.trim().isEmpty()) {
			return environmentValue;
		}
		return "";
	}
}
