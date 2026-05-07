package com.tradingbot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class FuturesManager {
	private static final String DATA_DIR = "market_data/futures";
	private static final String TIMEFRAME_FOLDER = "1min";
	private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");
	private static final DateTimeFormatter DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final LocalTime RTH_START = LocalTime.of(9, 30);
	private static final LocalTime RTH_END = LocalTime.of(16, 0);
	private static final LocalTime FORCED_EXIT_TIME = LocalTime.of(15, 55);
	private static final LocalTime ORB_END = LocalTime.of(9, 45);
	private static final LocalTime ORB_CUTOFF = LocalTime.of(11, 0);
	private static final LocalTime VWAP_START = LocalTime.of(9, 45);
	private static final LocalTime VWAP_END = LocalTime.of(15, 30);
	private static final LocalTime SWEEP_START = LocalTime.of(13, 0);
	private static final LocalTime SWEEP_END = LocalTime.of(14, 30);
	private static final LocalTime AFTERNOON_CONTINUATION_START = LocalTime.of(13, 0);
	private static final LocalTime AFTERNOON_CONTINUATION_END = LocalTime.of(15, 20);
	private static final LocalTime KELTNER_SCALP_START = LocalTime.of(9, 45);
	private static final LocalTime KELTNER_SCALP_END = LocalTime.of(15, 20);
	private static final LocalTime MEAN_REVERSION_START = LocalTime.of(11, 30);
	private static final LocalTime MEAN_REVERSION_END = LocalTime.of(15, 15);
	private static final double ORB_LONG_MIN_CLOSE_LOCATION = 0.58;
	private static final double ORB_LONG_MAX_CLOSE_LOCATION = 0.68;
	private static final LocalTime ORB_SHORT_CONFIRMATION_TIME = LocalTime.of(9, 50);
	private static final double DEFAULT_SWEEP_MAX_RISK_TICKS = 160.0;
	private static final LocalTime OPENING_MOMENTUM_END = LocalTime.of(11, 0);
	private static final LocalTime CLOSE_MOMENTUM_START = LocalTime.of(14, 30);
	private static final LocalTime CLOSE_MOMENTUM_END = LocalTime.of(15, 25);
	private static final LocalTime MARKET_INTRADAY_MOMENTUM_OPEN_END = LocalTime.of(10, 0);
	private static final LocalTime MARKET_IMPULSE_PULLBACK_START = LocalTime.of(10, 15);
	private static final LocalTime MARKET_IMPULSE_PULLBACK_END = LocalTime.of(14, 45);
	private static final LocalTime MARKET_INTRADAY_MOMENTUM_LATE_START = LocalTime.of(14, 30);
	private static final LocalTime MARKET_INTRADAY_MOMENTUM_SIGNAL_START = LocalTime.of(15, 25);
	private static final LocalTime MARKET_INTRADAY_MOMENTUM_SIGNAL_END = LocalTime.of(15, 35);
	private static final int RSI_PERIOD = 14;
	private static final String TOPSTEPX_PRACTICE_ACCOUNT_ID = "22539378";
	private static final String TOPSTEPX_50K_COMBINE_ACCOUNT_ID = "22529998";
	private static final String TOPSTEPX_PRACTICE_ACCOUNT_MODE = "PRACTICE_COMBINE";
	private static final String PRACTICE_ORDER_ARM_CONFIRMATION = "ARM_PRACTICE_22539378";
	private static final String DEFAULT_LIVE_SNAPSHOT_SOURCE_ID = "242";
	private static final String STRATEGY_SLOT_BACKTEST = "BACKTEST";
	private static final String STRATEGY_SLOT_LIVE = "LIVE";
	private static final ScheduledExecutorService LIVE_AUTOMATION_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "futures-live-automation");
		thread.setDaemon(true);
		return thread;
	});

	private static FuturesLiveSession liveSession = new FuturesLiveSession();
	private static ScheduledFuture<?> liveAutomationTask;
	private static boolean startupSafetyLockoutApplied;
	private static final String[] LIVE_GRAPH_TIMEFRAMES = new String[] { "1m", "5m", "30m", "1h" };
	private static final long LIVE_WARMUP_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(6L);
	private static final Map<String, LiveWarmupBars> LIVE_WARMUP_CACHE = new HashMap<String, LiveWarmupBars>();
	private static final Set<String> LIVE_GRAPH_WARMUP_LOADING = new HashSet<String>();
	private static final ExecutorService LIVE_GRAPH_WARMUP_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "futures-graph-warmup");
		thread.setDaemon(true);
		return thread;
	});

	public static class InstrumentSpec {
		public String symbol;
		public String name;
		public String exchange;
		public double tickSize;
		public double tickValue;
		public double pointValue;
		public int defaultMaxContracts;
		public double defaultStopTicks;
		public double defaultTargetR;
		public String proxySymbol;
		public double proxyScale;
	}

	private static class Bar {
		private String displayTime;
		private LocalDate marketDate;
		private LocalTime marketTime;
		private double open;
		private double high;
		private double low;
		private double close;
		private double volume;
		private double vwap;
		private double ema9;
		private double ema20;
		private double ema50;
		private double atr14;
		private double rsi14;
		private double volumeSma20;
		private double rangeTicks;
		private double bodyPct;
	}

	private static class Signal {
		private String strategyCode;
		private String strategyName;
		private String side;
		private int entryIndex;
		private double entryPrice;
		private double stopPrice;
		private double targetPrice;
		private int maxHoldBars;
		private String notes;
	}

	private static class RealtimePricePoint {
		private int id;
		private String time;
		private String eventType;
		private double price;
		private double volume;
		private boolean pollSnapshot;
	}

	private static class RealtimeCandle {
		private String time;
		private String eventType;
		private double open;
		private double high;
		private double low;
		private double close;
		private double volume;
		private double vwap;
		private double ema9;
		private double ema20;
		private double rsi14;
		private int events;
		private int pollEvents;
		private boolean live;
	}

	private static class LiveMonitorSeries {
		private String pointsJson = "[]";
		private double firstPrice;
		private double lastPrice;
		private String lastTime = "";
		private String dataSource = "LOCAL_CACHED_BARS";
		private int localBars;
		private int liveEvents;
		private int pollEvents;
	}

	private static class LiveWarmupBars {
		private List<Bar> bars = new ArrayList<Bar>();
		private String dataSource = "EMPTY";
		private long loadedAt;
	}

	private static class LiveSignalAnalysis {
		private String strategiesJson = "[]";
		private String latestSignalsJson = "[]";
		private String lastSignalCode = "";
		private String lastSignalName = "";
		private String lastSignalSide = "";
		private String lastSignalTime = "";
		private int enabledCount;
		private int signalCount;
	}

	private static class FuturesTrade {
		private String symbol;
		private String strategyCode;
		private String strategyName;
		private String side;
		private int contracts;
		private double entryPrice;
		private double exitPrice;
		private double stopPrice;
		private double targetPrice;
		private String openedAt;
		private String closedAt;
		private double pnl;
		private double mfe;
		private double mae;
		private String exitReason;
		private String notes;
		private int entryIndex;
		private int exitIndex;
		private LocalTime openedMarketTime;
		private LocalTime closedMarketTime;
	}

	private static class BacktestResult {
		private int id;
		private String symbol;
		private String contractName;
		private String startDate;
		private String endDate;
		private double startingBalance;
		private double endingBalance;
		private double totalProfit;
		private double returnPct;
		private double winRate;
		private int trades;
		private double profitFactor;
		private double maxDrawdownPct;
		private double trailingThreshold;
		private boolean ruleViolation;
		private String ruleMessage;
		private String dataSource;
		private List<FuturesTrade> tradeRecords = new ArrayList<FuturesTrade>();
	}

	private static class SignalStats {
		private String code;
		private String name;
		private int generated;
		private int executable;
		private int riskRejected;
		private int lateRejected;
		private int contractRejected;
		private int actualTrades;
	}

	private static class BarStats {
		private int rows;
		private int days;
		private String first = "";
		private String last = "";
	}

	private static class BacktestConfig {
		private String symbol;
		private String fundedProfile = "CUSTOM";
		private LocalDate startDate;
		private LocalDate endDate;
		private double accountSize;
		private double maxTrailingDrawdown;
		private double dailyLossLimit;
		private double maxRiskPerTrade;
		private int maxContracts;
		private double commissionPerContract;
		private double slippageTicks;
		private double profitTarget;
		private String trailingDrawdownMode = "INTRADAY";
		private FuturesStrategySettings strategySettings = defaultFuturesStrategySettings();
	}

	private static class PortfolioBacktestConfig {
		private String fundedProfile = "CUSTOM";
		private List<String> symbols = new ArrayList<String>();
		private String strategySlot = STRATEGY_SLOT_BACKTEST;
		private LocalDate startDate;
		private LocalDate endDate;
		private double accountSize;
		private double maxTrailingDrawdown;
		private double dailyLossLimit;
		private double maxRiskPerTrade;
		private int maxContracts;
		private double commissionPerContract;
		private double slippageTicks;
		private double profitTarget;
		private int maxOpenPositions;
		private int maxAggregateContracts;
		private double maxAggregateMiniUnits;
		private String trailingDrawdownMode = "INTRADAY";
		private boolean useSavedRisk;
	}

	private static class PortfolioBacktestResult {
		private int id;
		private String fundedProfile = "CUSTOM";
		private String symbols;
		private String startDate;
		private String endDate;
		private double startingBalance;
		private double endingBalance;
		private double totalProfit;
		private double returnPct;
		private double winRate;
		private int trades;
		private double profitFactor;
		private double maxDrawdownPct;
		private double trailingThreshold;
		private int maxConcurrentPositions;
		private int maxConcurrentContracts;
		private double maxConcurrentMiniUnits;
		private double maxNotionalExposure;
		private double maxIntradayLoss;
		private double maxAggregateMae;
		private int dailyLossBreaches;
		private int trailingDrawdownBreaches;
		private int maeBreaches;
		private int overlapRejections;
		private int exposureRejections;
		private int riskRejections;
		private boolean ruleViolation;
		private String ruleMessage;
		private String dataSource;
		private List<FuturesTrade> tradeRecords = new ArrayList<FuturesTrade>();
	}

	public static class FundedRuleProfile {
		public String code;
		public String name;
		public String provider;
		public double accountSize;
		public double maxTrailingDrawdown;
		public double dailyLossLimit;
		public double maxRiskPerTrade;
		public int maxContracts;
		public int maxMicroContracts;
		public int maxOpenPositions;
		public int maxAggregateContracts;
		public double maxAggregateMiniUnits;
		public double profitTarget;
		public String trailingDrawdownMode;
		public String dailySession;
		public String forceFlatTime;
		public String notes;
	}

	private static class PortfolioSymbolContext {
		private String symbol;
		private InstrumentSpec spec;
		private BacktestConfig config;
		private DataBundle bars;
		private Map<LocalDate, List<Bar>> byDay = new HashMap<LocalDate, List<Bar>>();
		private Map<LocalDate, List<Bar>> fifteenMinuteByDay = new HashMap<LocalDate, List<Bar>>();
		private Map<LocalDate, List<Bar>> oneHourByDay = new HashMap<LocalDate, List<Bar>>();
		private Map<LocalDate, Map<LocalTime, Integer>> indexByDayTime = new HashMap<LocalDate, Map<LocalTime, Integer>>();
		private Map<LocalDate, List<SignalEvent>> eventsByDay = new HashMap<LocalDate, List<SignalEvent>>();
	}

	private static class SignalEvent {
		private String symbol;
		private Signal signal;
		private LocalDate day;
		private LocalTime entryTime;
		private int executionIndex;
	}

	private static class PortfolioPosition {
		private String symbol;
		private InstrumentSpec spec;
		private Signal signal;
		private String side;
		private int contracts;
		private double entryPrice;
		private double stopPrice;
		private double targetPrice;
		private double activeStopPrice;
		private double initialRisk;
		private double minTrailDistance;
		private double riskPerContract;
		private double commissionPerContract;
		private double maxFavorable;
		private double maxAdverse;
		private int entryIndex;
		private String openedAt;
		private LocalTime openedMarketTime;
		private int concurrentPositionsAtEntry;
		private int concurrentContractsAtEntry;
	}

		public static class FuturesStrategySettings {
			public StrategyToggle orb = new StrategyToggle(true, 1);
			public StrategyToggle openingMomentum = new StrategyToggle(false, 2);
			public StrategyToggle sweep = new StrategyToggle(true, 3);
			public StrategyToggle vwapPullback = new StrategyToggle(false, 1);
			public StrategyToggle vwapMeanReversion = new StrategyToggle(false, 1);
		public StrategyToggle fvg = new StrategyToggle(false, 1);
		public StrategyToggle closeMomentum = new StrategyToggle(false, 1);
		public StrategyToggle afternoonContinuation = new StrategyToggle(false, 2);
		public StrategyToggle marketIntradayMomentum = new StrategyToggle(false, 1);
		public StrategyToggle keltnerScalp = new StrategyToggle(false, 8);
		public StrategyToggle keltnerReversion = new StrategyToggle(false, 6);
		public StrategyToggle microScalp = new StrategyToggle(false, 6);
		public boolean enableEarlySweep = true;
		public boolean enableLateSweep = true;
		public boolean enableSweepSecondChance = true;
		public boolean enableOrbRetest = false;
		public boolean allowOrbRetestLongs = true;
		public boolean allowOrbRetestShorts = true;
		public int orbRetestStartMinutes = 0;
		public int orbRetestEndMinutes = 135;
		public boolean enableCompressedOrbBreakout = false;
		public boolean skipMidmorningOrbRetest = false;
		public boolean requireHigherTimeframeGuard = true;
		public boolean allowShorts = true;
		public int openingMomentumRangeMinutes = 10;
		public int openingMomentumMaxHoldBars = 120;
		public double openingMomentumVolumeRatio = 0.5;
		public double openingMomentumRewardRisk = 0.8;
		public double earlySweepReclaimTicks = 6.0;
		public double lateSweepReclaimTicks = 8.0;
		public double sweepCloseLocation = 0.6;
		public double lateSweepCloseLocation = 0.45;
		public double minBodyPct = 28.0;
		public double vwapMinVolumeRatio = 1.05;
		public double vwapMinTrendSlopeTicks = 3.0;
		public double vwapMaxDistanceTicks = 36.0;
		public double vwapMaxRiskTicks = 28.0;
		public double meanReversionMinDistanceTicks = 36.0;
		public double meanReversionOversoldRsi = 30.0;
		public double meanReversionOverboughtRsi = 70.0;
		public double minRewardRisk = 1.15;
		public boolean allowCloseMomentumLongs = true;
		public boolean allowCloseMomentumShorts = true;
		public double closeMomentumMinMoveTicks = 24.0;
		public double closeMomentumVolumeRatio = 0.8;
		public double closeMomentumRewardRisk = 0.9;
		public double orbCompressedMaxRiskTicks = 60.0;
		public double orbRetestMaxRiskTicks = 220.0;
		public double afternoonMinVolumeRatio = 0.9;
		public double afternoonMaxRiskTicks = 48.0;
		public double afternoonRewardRisk = 1.0;
		public double marketIntradayMomentumMinOpenMoveTicks = 12.0;
		public double marketIntradayMomentumMinLateMoveTicks = 8.0;
		public double marketIntradayMomentumMinVolumeRatio = 0.6;
		public double marketIntradayMomentumMaxRiskTicks = 48.0;
		public double marketIntradayMomentumRewardRisk = 0.8;
		public boolean allowKeltnerScalpLongs = true;
		public boolean allowKeltnerScalpShorts = true;
		public double keltnerAtrMultiplier = 1.3;
		public double keltnerMinVolumeRatio = 0.75;
		public double keltnerMaxRiskTicks = 22.0;
		public double keltnerRewardRisk = 0.85;
		public double keltnerMinBodyPct = 16.0;
		public double keltnerMinTrendSlopeTicks = 0.5;
		public double keltnerMinBandWidthTicks = 8.0;
		public int keltnerMaxHoldBars = 10;
		public int keltnerBucketMinutes = 12;
		public double microScalpMinVolumeRatio = 0.75;
		public double microScalpMaxRiskTicks = 18.0;
		public double microScalpRewardRisk = 0.85;
		public double microScalpMinBodyPct = 18.0;
		public double microScalpMinTrendSlopeTicks = 0.5;
		public int microScalpMaxHoldBars = 10;
		public int microScalpBucketMinutes = 20;
			public double maxInitialRiskTicks = 220.0;
		public boolean enableAdaptiveExits = false;
		public double adaptiveMinVolumeRatio = 1.15;
		public double adaptiveMinBodyPct = 35.0;
		public double adaptiveTrendTargetBoost = 0.25;
		public double adaptiveVolumeTargetBoost = 0.20;
		public double adaptiveBodyTargetBoost = 0.15;
		public double adaptiveMaxRewardRisk = 2.2;
		public boolean enableEarlyLossCut = false;
		public int earlyLossCutBars = 18;
		public double earlyLossCutR = 0.65;
		public double earlyLossCutMinFavorableR = 0.20;
		public double openMaeRiskMultiplier = 1.0;
		}

	public static class StrategyToggle {
		public boolean enabled;
		public int maxTradesPerDay;

		public StrategyToggle(boolean enabled, int maxTradesPerDay) {
			this.enabled = enabled;
			this.maxTradesPerDay = maxTradesPerDay;
		}
	}

	public static class FuturesRiskSettings {
		public double accountSize = 50000.0;
		public double maxTrailingDrawdown = 2000.0;
		public double dailyLossLimit = 1000.0;
		public double maxRiskPerTrade = 400.0;
		public int maxContracts = 50;
		public double commissionPerContract = 1.24;
		public double slippageTicks = 1.0;
		public double profitTarget = 3000.0;
	}

	private static class FuturesLiveSession {
		private boolean running;
		private int sessionId;
		private String symbol = "MNQ";
		private String executionMode = "SIMULATED";
		private String fundedProfile = "TOPSTEP_50K_COMBINE";
		private String symbols = "MNQ";
		private String startedAt = "";
		private String lastUpdatedAt = "";
		private String lastDryRunAt = "";
		private String dataMode = "IDLE";
		private String lastBarTime = "";
		private double accountSize = 50000.0;
		private double maxTrailingDrawdown = 2000.0;
		private double dailyLossLimit = 1000.0;
		private double maxRiskPerTrade = 400.0;
		private int maxContracts = 50;
		private double maxAggregateMiniUnits = 5.0;
		private int decisionCount;
		private int acceptedDecisionCount;
		private int rejectedDecisionCount;
		private String lastDecision = "Futures live runner is idle.";
		private String lastProcessedLiveBarTime = "";
		private int automationCycles;
		private boolean flattenAttempted;
	}

	private static class MarketSessionStatus {
		private boolean tradingDay;
		private boolean rthOpen;
		private boolean entryWindowOpen;
		private String code;
		private String label;
		private String detail;
		private String now;
		private String nextAction;
	}

	private static class LiveStrategySnapshotRow {
		private int snapshotId;
		private int sourcePortfolioBacktestId;
		private String symbols;
		private String fundedProfile;
		private String accountMode;
		private String practiceAccountId;
		private int maxOpenPositions;
		private int maxAggregateContracts;
		private double maxAggregateMiniUnits;
		private String strategySettingsJson;
		private String riskSettingsJson;
		private String portfolioSettingsJson;
		private String sourceMetricsJson;
		private String codeVersion;
		private String createdAt;
		private String updatedAt;
	}

	private static class OrderArmingState {
		private boolean armed;
		private String accountId = TOPSTEPX_PRACTICE_ACCOUNT_ID;
		private String mode = "GUARDED";
		private String armedAt = "";
		private String disarmedAt = "";
		private String message = "Practice order submission is guarded.";
	}

	public static synchronized void initializeStore() {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesBacktests ("
					+ "futuresBacktestID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "symbol TEXT, contractName TEXT, startDate TEXT, endDate TEXT, "
					+ "startingBalance REAL, endingBalance REAL, totalProfit REAL, returnPct REAL, "
					+ "winRate REAL, numTrades INTEGER, profitFactor REAL, maxDrawdownPct REAL, "
					+ "maxTrailingDrawdown REAL, dailyLossLimit REAL, maxRiskPerTrade REAL, maxContracts INTEGER, "
					+ "trailingThreshold REAL, ruleViolation INTEGER, ruleMessage TEXT, dataSource TEXT, createdAt TEXT"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesTrades ("
					+ "futuresTradeID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "futuresBacktestID INTEGER, symbol TEXT, strategyCode TEXT, strategyName TEXT, side TEXT, "
					+ "contracts INTEGER, entryPrice REAL, exitPrice REAL, stopPrice REAL, targetPrice REAL, "
					+ "openedAt TEXT, closedAt TEXT, pnl REAL, mfe REAL, mae REAL, exitReason TEXT, tradeNotes TEXT, "
					+ "FOREIGN KEY (futuresBacktestID) REFERENCES FuturesBacktests(futuresBacktestID)"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesPortfolioBacktests ("
					+ "portfolioBacktestID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "symbols TEXT, startDate TEXT, endDate TEXT, "
					+ "startingBalance REAL, endingBalance REAL, totalProfit REAL, returnPct REAL, "
					+ "winRate REAL, numTrades INTEGER, profitFactor REAL, maxDrawdownPct REAL, "
					+ "maxTrailingDrawdown REAL, dailyLossLimit REAL, maxRiskPerTrade REAL, maxContracts INTEGER, "
					+ "maxOpenPositions INTEGER, maxAggregateContracts INTEGER, maxConcurrentPositions INTEGER, "
					+ "maxConcurrentContracts INTEGER, maxNotionalExposure REAL, maxIntradayLoss REAL, "
					+ "maxAggregateMae REAL, trailingThreshold REAL, dailyLossBreaches INTEGER, "
					+ "trailingDrawdownBreaches INTEGER, maeBreaches INTEGER, overlapRejections INTEGER, "
					+ "exposureRejections INTEGER, riskRejections INTEGER, ruleViolation INTEGER, "
					+ "ruleMessage TEXT, dataSource TEXT, createdAt TEXT"
					+ ")"
			);
			ensureColumn(stmt, "FuturesPortfolioBacktests", "fundedProfile", "TEXT DEFAULT 'CUSTOM'");
			ensureColumn(stmt, "FuturesPortfolioBacktests", "maxAggregateMiniUnits", "REAL DEFAULT 0");
			ensureColumn(stmt, "FuturesPortfolioBacktests", "maxConcurrentMiniUnits", "REAL DEFAULT 0");
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesPortfolioTrades ("
					+ "portfolioTradeID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "portfolioBacktestID INTEGER, symbol TEXT, strategyCode TEXT, strategyName TEXT, side TEXT, "
					+ "contracts INTEGER, entryPrice REAL, exitPrice REAL, stopPrice REAL, targetPrice REAL, "
					+ "openedAt TEXT, closedAt TEXT, pnl REAL, mfe REAL, mae REAL, exitReason TEXT, tradeNotes TEXT, "
					+ "FOREIGN KEY (portfolioBacktestID) REFERENCES FuturesPortfolioBacktests(portfolioBacktestID)"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesStrategySettings ("
					+ "settingKey TEXT PRIMARY KEY, "
					+ "settingValue TEXT"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesLiveStrategySnapshots ("
					+ "snapshotID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "sourcePortfolioBacktestID INTEGER, active INTEGER DEFAULT 1, "
					+ "symbols TEXT, fundedProfile TEXT, accountMode TEXT, practiceAccountId TEXT, "
					+ "maxOpenPositions INTEGER, maxAggregateContracts INTEGER, maxAggregateMiniUnits REAL, "
					+ "strategySettingsJson TEXT, riskSettingsJson TEXT, portfolioSettingsJson TEXT, sourceMetricsJson TEXT, "
					+ "codeVersion TEXT, createdAt TEXT, updatedAt TEXT, "
					+ "FOREIGN KEY (sourcePortfolioBacktestID) REFERENCES FuturesPortfolioBacktests(portfolioBacktestID)"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesLiveOrderLedger ("
					+ "liveOrderID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "snapshotID INTEGER, accountId TEXT, symbol TEXT, side TEXT, orderType TEXT, contracts INTEGER, "
					+ "entryPrice REAL, stopPrice REAL, targetPrice REAL, status TEXT, "
					+ "requestJson TEXT, responseJson TEXT, createdAt TEXT, updatedAt TEXT, "
					+ "FOREIGN KEY (snapshotID) REFERENCES FuturesLiveStrategySnapshots(snapshotID)"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesLiveEngineSessions ("
					+ "sessionID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "snapshotID INTEGER, sourcePortfolioBacktestID INTEGER, executionMode TEXT, status TEXT, "
					+ "symbols TEXT, dataMode TEXT, startedAt TEXT, lastUpdatedAt TEXT, lastBarTime TEXT, "
					+ "decisionCount INTEGER DEFAULT 0, acceptedDecisionCount INTEGER DEFAULT 0, rejectedDecisionCount INTEGER DEFAULT 0, "
					+ "message TEXT, FOREIGN KEY (snapshotID) REFERENCES FuturesLiveStrategySnapshots(snapshotID)"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesLiveSignalDecisions ("
					+ "decisionID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "sessionID INTEGER, snapshotID INTEGER, symbol TEXT, strategyCode TEXT, strategyName TEXT, side TEXT, "
					+ "signalTime TEXT, entryTime TEXT, contracts INTEGER, entryPrice REAL, stopPrice REAL, targetPrice REAL, "
					+ "fundedMiniUnits REAL, status TEXT, reason TEXT, payloadJson TEXT, createdAt TEXT, "
					+ "FOREIGN KEY (sessionID) REFERENCES FuturesLiveEngineSessions(sessionID), "
					+ "FOREIGN KEY (snapshotID) REFERENCES FuturesLiveStrategySnapshots(snapshotID)"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesLiveRiskEvents ("
					+ "riskEventID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "sessionID INTEGER, snapshotID INTEGER, eventType TEXT, severity TEXT, message TEXT, payloadJson TEXT, createdAt TEXT, "
					+ "FOREIGN KEY (sessionID) REFERENCES FuturesLiveEngineSessions(sessionID), "
					+ "FOREIGN KEY (snapshotID) REFERENCES FuturesLiveStrategySnapshots(snapshotID)"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesLiveAuditLog ("
					+ "auditID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "eventType TEXT, severity TEXT, message TEXT, payloadJson TEXT, createdAt TEXT"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesLiveOrderArming ("
					+ "stateID INTEGER PRIMARY KEY CHECK (stateID = 1), "
					+ "armed INTEGER DEFAULT 0, accountId TEXT, mode TEXT, armedAt TEXT, disarmedAt TEXT, message TEXT"
					+ ")"
			);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		if (!startupSafetyLockoutApplied) {
			applyStartupSafetyLockout();
			startupSafetyLockoutApplied = true;
		}
	}

	private static void ensureColumn(Statement stmt, String tableName, String columnName, String definition) {
		try {
			stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
		} catch (SQLException e) {
			String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
			if (!message.contains("duplicate column")) {
				e.printStackTrace();
			}
		}
	}

	private static void applyStartupSafetyLockout() {
		String now = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		String message = "Backend restarted; practice order submission is guarded until a fresh preflight and explicit arming pass.";
		try (Connection conn = DatabaseManager.getConnection()) {
			try (PreparedStatement pstmt = conn.prepareStatement(
				"INSERT OR REPLACE INTO FuturesLiveOrderArming (stateID, armed, accountId, mode, armedAt, disarmedAt, message) VALUES (1, 0, ?, 'GUARDED', '', ?, ?)"
			)) {
				pstmt.setString(1, TOPSTEPX_PRACTICE_ACCOUNT_ID);
				pstmt.setString(2, now);
				pstmt.setString(3, message);
				pstmt.executeUpdate();
			}
			try (PreparedStatement pstmt = conn.prepareStatement(
				"UPDATE FuturesLiveEngineSessions SET status = 'RESTART_LOCKOUT', lastUpdatedAt = ?, message = ? WHERE status = 'RUNNING'"
			)) {
				pstmt.setString(1, now);
				pstmt.setString(2, "Backend restarted while this session was marked running; restart-safe preflight is required before any new order submission.");
				pstmt.executeUpdate();
			}
			try (PreparedStatement pstmt = conn.prepareStatement(
				"INSERT INTO FuturesLiveAuditLog (eventType, severity, message, payloadJson, createdAt) VALUES (?, ?, ?, ?, ?)"
			)) {
				pstmt.setString(1, "STARTUP_ORDER_LOCKOUT");
				pstmt.setString(2, "WARN");
				pstmt.setString(3, message);
				pstmt.setString(4, "{\"accountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + "}");
				pstmt.setString(5, now);
				pstmt.executeUpdate();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public static String getInstrumentJson() {
		List<InstrumentSpec> specs = supportedInstruments();
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < specs.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			InstrumentSpec spec = specs.get(index);
			json.append("{")
				.append("\"symbol\":").append(jsonString(spec.symbol)).append(",")
				.append("\"name\":").append(jsonString(spec.name)).append(",")
				.append("\"exchange\":").append(jsonString(spec.exchange)).append(",")
				.append("\"tickSize\":").append(spec.tickSize).append(",")
				.append("\"tickValue\":").append(spec.tickValue).append(",")
				.append("\"pointValue\":").append(spec.pointValue).append(",")
				.append("\"defaultMaxContracts\":").append(spec.defaultMaxContracts).append(",")
				.append("\"defaultStopTicks\":").append(spec.defaultStopTicks).append(",")
				.append("\"defaultTargetR\":").append(spec.defaultTargetR)
				.append("}");
		}
		json.append("]");
		return json.toString();
	}

	public static String getMarketDataStatusJson() {
		List<InstrumentSpec> specs = supportedInstruments();
		StringBuilder json = new StringBuilder("{\"storagePath\":\"")
			.append(DATA_DIR)
			.append("\",\"timeframe\":\"1Min + derived 5Min/15Min/1Hour\",")
			.append("\"fields\":\"timestamp,open,high,low,close,volume,vwap,ema9,ema20,ema50,atr14,rsi14,volume_sma20,range_ticks,body_pct\",")
			.append("\"timeframes\":[\"1min\",\"5min\",\"15min\",\"1hour\"],\"symbols\":[");
		for (int index = 0; index < specs.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			json.append(jsonString(specs.get(index).symbol));
		}
		json.append("],\"rowsBySymbol\":{");
		for (int index = 0; index < specs.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			InstrumentSpec spec = specs.get(index);
			DataBundle bundle = loadNativeFuturesBars(spec.symbol, LocalDate.now().minusYears(2), LocalDate.now(), TIMEFRAME_FOLDER);
			json.append(jsonString(spec.symbol)).append(":").append(bundle.bars.size());
		}
		json.append("},\"rawRowsBySymbol\":{");
		for (int index = 0; index < specs.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			InstrumentSpec spec = specs.get(index);
			json.append(jsonString(spec.symbol)).append(":")
				.append(countCsvDataRows(new File(DATA_DIR + "/" + TIMEFRAME_FOLDER + "/" + spec.symbol + ".csv")));
		}
		json.append("},\"message\":")
			.append(jsonString("Futures data lives under backend/market_data/futures. Production backtests use native 1-minute bars plus derived 5-minute, 15-minute, and 1-hour context."))
			.append("}");
		return json.toString();
	}

	public static int generateBacktest(
		String symbol,
		String startDate,
		String endDate,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double commissionPerContract,
		double slippageTicks,
		double profitTarget
	) {
		return generateBacktest(
			symbol,
			startDate,
			endDate,
			accountSize,
			maxTrailingDrawdown,
			dailyLossLimit,
			maxRiskPerTrade,
			maxContracts,
			commissionPerContract,
			slippageTicks,
			profitTarget,
			"CUSTOM"
		);
	}

	public static int generateBacktest(
		String symbol,
		String startDate,
		String endDate,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double commissionPerContract,
		double slippageTicks,
		double profitTarget,
		String fundedProfile
	) {
		initializeStore();
		BacktestConfig config = new BacktestConfig();
		config.symbol = normalizeSymbol(symbol);
		FuturesRiskSettings riskSettings = loadFuturesRiskSettings(config.symbol);
		FundedRuleProfile profile = fundedRuleProfileFor(fundedProfile);
		config.startDate = parseDate(startDate, LocalDate.now().minusYears(1));
		config.endDate = parseDate(endDate, LocalDate.now());
		config.accountSize = positiveOrDefault(accountSize, riskSettings.accountSize);
		config.maxTrailingDrawdown = positiveOrDefault(maxTrailingDrawdown, riskSettings.maxTrailingDrawdown);
		config.dailyLossLimit = positiveOrDefault(dailyLossLimit, riskSettings.dailyLossLimit);
		config.maxRiskPerTrade = positiveOrDefault(maxRiskPerTrade, riskSettings.maxRiskPerTrade);
		config.maxContracts = boundedInt(maxContracts, riskSettings.maxContracts, 1, 50);
		config.commissionPerContract = commissionPerContract >= 0.0 ? commissionPerContract : riskSettings.commissionPerContract;
		config.slippageTicks = slippageTicks >= 0.0 ? slippageTicks : riskSettings.slippageTicks;
		config.profitTarget = profitTarget >= 0.0 ? profitTarget : riskSettings.profitTarget;
		config.fundedProfile = profile.code;
		config.trailingDrawdownMode = profile.trailingDrawdownMode;
		config.strategySettings = loadFuturesStrategySettings(config.symbol);
		applyFundedProfile(config, profile);

		BacktestResult result = runBacktest(config);
		if (result == null) {
			return -1;
		}
		return saveBacktest(result, config);
	}

	public static int generatePortfolioBacktest(
		String symbols,
		String startDate,
		String endDate,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double commissionPerContract,
		double slippageTicks,
		int maxOpenPositions,
		int maxAggregateContracts,
		boolean useSavedRisk,
		double profitTarget
	) {
		return generatePortfolioBacktest(
			symbols,
			startDate,
			endDate,
			accountSize,
			maxTrailingDrawdown,
			dailyLossLimit,
			maxRiskPerTrade,
			maxContracts,
			commissionPerContract,
			slippageTicks,
			maxOpenPositions,
			maxAggregateContracts,
			0.0,
			useSavedRisk,
			profitTarget,
			"CUSTOM"
		);
	}

	public static int generatePortfolioBacktest(
		String symbols,
		String startDate,
		String endDate,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double commissionPerContract,
		double slippageTicks,
		int maxOpenPositions,
		int maxAggregateContracts,
		double maxAggregateMiniUnits,
		boolean useSavedRisk,
		double profitTarget,
		String fundedProfile
	) {
		initializeStore();
		PortfolioBacktestConfig config = buildPortfolioBacktestConfig(
			symbols,
			startDate,
			endDate,
			accountSize,
			maxTrailingDrawdown,
			dailyLossLimit,
			maxRiskPerTrade,
			maxContracts,
			commissionPerContract,
			slippageTicks,
			maxOpenPositions,
			maxAggregateContracts,
			maxAggregateMiniUnits,
			useSavedRisk,
			profitTarget,
			fundedProfile
		);
		PortfolioBacktestResult result = runPortfolioBacktest(config);
		if (result == null) {
			return -1;
		}
		return savePortfolioBacktest(result, config);
	}

	public static FuturesStrategySettings loadFuturesStrategySettings() {
		return loadFuturesStrategySettings("MNQ");
	}

	public static FuturesStrategySettings loadFuturesStrategySettings(String symbol) {
		initializeStore();
		String normalizedSymbol = normalizeSymbol(symbol);
		FuturesStrategySettings settings = defaultFuturesStrategySettings();
		String sql = "SELECT settingKey, settingValue FROM FuturesStrategySettings";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				String key = rs.getString("settingKey");
				if (!isSymbolScopedSettingKey(key)) {
					applyFuturesSetting(settings, key, rs.getString("settingValue"));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				String unprefixedKey = unprefixedSymbolSettingKey(normalizedSymbol, rs.getString("settingKey"));
				if (unprefixedKey != null) {
					applyFuturesSetting(settings, unprefixedKey, rs.getString("settingValue"));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		clampFuturesStrategySettings(settings);
		return settings;
	}

	public static FuturesStrategySettings loadFuturesStrategySettings(String symbol, String slot) {
		String normalizedSlot = normalizeStrategySlot(slot);
		if (STRATEGY_SLOT_BACKTEST.equals(normalizedSlot)) {
			return loadFuturesStrategySettings(symbol);
		}
		initializeStore();
		String normalizedSymbol = normalizeSymbol(symbol);
		FuturesStrategySettings settings = defaultFuturesStrategySettings();
		boolean hasSlotSettings = false;
		String sql = "SELECT settingKey, settingValue FROM FuturesStrategySettings";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				String unprefixedKey = unprefixedSlotSymbolSettingKey(normalizedSlot, normalizedSymbol, rs.getString("settingKey"));
				if (unprefixedKey != null) {
					hasSlotSettings = true;
					applyFuturesSetting(settings, unprefixedKey, rs.getString("settingValue"));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		if (!hasSlotSettings) {
			return loadFuturesStrategySettings(symbol);
		}
		clampFuturesStrategySettings(settings);
		return settings;
	}

	public static boolean saveFuturesStrategySettings(FuturesStrategySettings settings) {
		return saveFuturesStrategySettings("MNQ", settings);
	}

	public static boolean saveFuturesStrategySettings(String symbol, FuturesStrategySettings settings) {
		initializeStore();
		String normalizedSymbol = normalizeSymbol(symbol);
		FuturesStrategySettings safeSettings = settings == null ? defaultFuturesStrategySettings() : settings;
		clampFuturesStrategySettings(safeSettings);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) VALUES (?, ?)")) {
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "orb.enabled"), safeSettings.orb.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "orb.maxTradesPerDay"), safeSettings.orb.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "openingMomentum.enabled"), safeSettings.openingMomentum.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "openingMomentum.maxTradesPerDay"), safeSettings.openingMomentum.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "sweep.enabled"), safeSettings.sweep.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "sweep.maxTradesPerDay"), safeSettings.sweep.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "vwapPullback.enabled"), safeSettings.vwapPullback.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "vwapPullback.maxTradesPerDay"), safeSettings.vwapPullback.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "vwapMeanReversion.enabled"), safeSettings.vwapMeanReversion.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "vwapMeanReversion.maxTradesPerDay"), safeSettings.vwapMeanReversion.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "fvg.enabled"), safeSettings.fvg.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "fvg.maxTradesPerDay"), safeSettings.fvg.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "closeMomentum.enabled"), safeSettings.closeMomentum.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "closeMomentum.maxTradesPerDay"), safeSettings.closeMomentum.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "afternoonContinuation.enabled"), safeSettings.afternoonContinuation.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "afternoonContinuation.maxTradesPerDay"), safeSettings.afternoonContinuation.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "marketIntradayMomentum.enabled"), safeSettings.marketIntradayMomentum.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "marketIntradayMomentum.maxTradesPerDay"), safeSettings.marketIntradayMomentum.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerScalp.enabled"), safeSettings.keltnerScalp.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerScalp.maxTradesPerDay"), safeSettings.keltnerScalp.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerReversion.enabled"), safeSettings.keltnerReversion.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerReversion.maxTradesPerDay"), safeSettings.keltnerReversion.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalp.enabled"), safeSettings.microScalp.enabled);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalp.maxTradesPerDay"), safeSettings.microScalp.maxTradesPerDay);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "enableEarlySweep"), safeSettings.enableEarlySweep);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "enableLateSweep"), safeSettings.enableLateSweep);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "enableSweepSecondChance"), safeSettings.enableSweepSecondChance);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "enableOrbRetest"), safeSettings.enableOrbRetest);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "allowOrbRetestLongs"), safeSettings.allowOrbRetestLongs);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "allowOrbRetestShorts"), safeSettings.allowOrbRetestShorts);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "orbRetestStartMinutes"), safeSettings.orbRetestStartMinutes);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "orbRetestEndMinutes"), safeSettings.orbRetestEndMinutes);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "enableCompressedOrbBreakout"), safeSettings.enableCompressedOrbBreakout);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "skipMidmorningOrbRetest"), safeSettings.skipMidmorningOrbRetest);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "requireHigherTimeframeGuard"), safeSettings.requireHigherTimeframeGuard);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "allowShorts"), safeSettings.allowShorts);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "openingMomentumRangeMinutes"), safeSettings.openingMomentumRangeMinutes);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "openingMomentumMaxHoldBars"), safeSettings.openingMomentumMaxHoldBars);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "openingMomentumVolumeRatio"), safeSettings.openingMomentumVolumeRatio);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "openingMomentumRewardRisk"), safeSettings.openingMomentumRewardRisk);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "earlySweepReclaimTicks"), safeSettings.earlySweepReclaimTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "lateSweepReclaimTicks"), safeSettings.lateSweepReclaimTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "sweepCloseLocation"), safeSettings.sweepCloseLocation);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "lateSweepCloseLocation"), safeSettings.lateSweepCloseLocation);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "minBodyPct"), safeSettings.minBodyPct);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "vwapMinVolumeRatio"), safeSettings.vwapMinVolumeRatio);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "vwapMinTrendSlopeTicks"), safeSettings.vwapMinTrendSlopeTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "vwapMaxDistanceTicks"), safeSettings.vwapMaxDistanceTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "vwapMaxRiskTicks"), safeSettings.vwapMaxRiskTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "meanReversionMinDistanceTicks"), safeSettings.meanReversionMinDistanceTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "meanReversionOversoldRsi"), safeSettings.meanReversionOversoldRsi);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "meanReversionOverboughtRsi"), safeSettings.meanReversionOverboughtRsi);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "minRewardRisk"), safeSettings.minRewardRisk);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "allowCloseMomentumLongs"), safeSettings.allowCloseMomentumLongs);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "allowCloseMomentumShorts"), safeSettings.allowCloseMomentumShorts);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "closeMomentumMinMoveTicks"), safeSettings.closeMomentumMinMoveTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "closeMomentumVolumeRatio"), safeSettings.closeMomentumVolumeRatio);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "closeMomentumRewardRisk"), safeSettings.closeMomentumRewardRisk);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "orbCompressedMaxRiskTicks"), safeSettings.orbCompressedMaxRiskTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "orbRetestMaxRiskTicks"), safeSettings.orbRetestMaxRiskTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "afternoonMinVolumeRatio"), safeSettings.afternoonMinVolumeRatio);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "afternoonMaxRiskTicks"), safeSettings.afternoonMaxRiskTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "afternoonRewardRisk"), safeSettings.afternoonRewardRisk);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "marketIntradayMomentumMinOpenMoveTicks"), safeSettings.marketIntradayMomentumMinOpenMoveTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "marketIntradayMomentumMinLateMoveTicks"), safeSettings.marketIntradayMomentumMinLateMoveTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "marketIntradayMomentumMinVolumeRatio"), safeSettings.marketIntradayMomentumMinVolumeRatio);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "marketIntradayMomentumMaxRiskTicks"), safeSettings.marketIntradayMomentumMaxRiskTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "marketIntradayMomentumRewardRisk"), safeSettings.marketIntradayMomentumRewardRisk);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "allowKeltnerScalpLongs"), safeSettings.allowKeltnerScalpLongs);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "allowKeltnerScalpShorts"), safeSettings.allowKeltnerScalpShorts);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerAtrMultiplier"), safeSettings.keltnerAtrMultiplier);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerMinVolumeRatio"), safeSettings.keltnerMinVolumeRatio);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerMaxRiskTicks"), safeSettings.keltnerMaxRiskTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerRewardRisk"), safeSettings.keltnerRewardRisk);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerMinBodyPct"), safeSettings.keltnerMinBodyPct);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerMinTrendSlopeTicks"), safeSettings.keltnerMinTrendSlopeTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerMinBandWidthTicks"), safeSettings.keltnerMinBandWidthTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerMaxHoldBars"), safeSettings.keltnerMaxHoldBars);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "keltnerBucketMinutes"), safeSettings.keltnerBucketMinutes);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalpMinVolumeRatio"), safeSettings.microScalpMinVolumeRatio);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalpMaxRiskTicks"), safeSettings.microScalpMaxRiskTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalpRewardRisk"), safeSettings.microScalpRewardRisk);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalpMinBodyPct"), safeSettings.microScalpMinBodyPct);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalpMinTrendSlopeTicks"), safeSettings.microScalpMinTrendSlopeTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalpMaxHoldBars"), safeSettings.microScalpMaxHoldBars);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "microScalpBucketMinutes"), safeSettings.microScalpBucketMinutes);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "maxInitialRiskTicks"), safeSettings.maxInitialRiskTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "enableAdaptiveExits"), safeSettings.enableAdaptiveExits);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "adaptiveMinVolumeRatio"), safeSettings.adaptiveMinVolumeRatio);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "adaptiveMinBodyPct"), safeSettings.adaptiveMinBodyPct);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "adaptiveTrendTargetBoost"), safeSettings.adaptiveTrendTargetBoost);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "adaptiveVolumeTargetBoost"), safeSettings.adaptiveVolumeTargetBoost);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "adaptiveBodyTargetBoost"), safeSettings.adaptiveBodyTargetBoost);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "adaptiveMaxRewardRisk"), safeSettings.adaptiveMaxRewardRisk);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "enableEarlyLossCut"), safeSettings.enableEarlyLossCut);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "earlyLossCutBars"), safeSettings.earlyLossCutBars);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "earlyLossCutR"), safeSettings.earlyLossCutR);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "earlyLossCutMinFavorableR"), safeSettings.earlyLossCutMinFavorableR);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "openMaeRiskMultiplier"), safeSettings.openMaeRiskMultiplier);
			pstmt.executeBatch();
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static String getFuturesStrategySettingsJson() {
		return getFuturesStrategySettingsJson("MNQ");
	}

	public static String getFuturesStrategySettingsJson(String symbol) {
		return getFuturesStrategySettingsJson(symbol, STRATEGY_SLOT_BACKTEST);
	}

	public static String getFuturesStrategySettingsJson(String symbol, String slot) {
		String normalizedSymbol = normalizeSymbol(symbol);
		String normalizedSlot = normalizeStrategySlot(slot);
		FuturesStrategySettings settings = loadFuturesStrategySettings(normalizedSymbol, normalizedSlot);
		return "{"
			+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
			+ "\"slot\":" + jsonString(normalizedSlot) + ","
			+ "\"availableSymbols\":" + jsonStringArray(supportedInstrumentSymbols()) + ","
			+ "\"orb\":" + toggleJson(settings.orb) + ","
			+ "\"openingMomentum\":" + toggleJson(settings.openingMomentum) + ","
			+ "\"sweep\":" + toggleJson(settings.sweep) + ","
			+ "\"vwapPullback\":" + toggleJson(settings.vwapPullback) + ","
			+ "\"vwapMeanReversion\":" + toggleJson(settings.vwapMeanReversion) + ","
			+ "\"fvg\":" + toggleJson(settings.fvg) + ","
			+ "\"closeMomentum\":" + toggleJson(settings.closeMomentum) + ","
			+ "\"afternoonContinuation\":" + toggleJson(settings.afternoonContinuation) + ","
			+ "\"marketIntradayMomentum\":" + toggleJson(settings.marketIntradayMomentum) + ","
			+ "\"keltnerScalp\":" + toggleJson(settings.keltnerScalp) + ","
			+ "\"keltnerReversion\":" + toggleJson(settings.keltnerReversion) + ","
			+ "\"microScalp\":" + toggleJson(settings.microScalp) + ","
			+ "\"enableEarlySweep\":" + settings.enableEarlySweep + ","
			+ "\"enableLateSweep\":" + settings.enableLateSweep + ","
			+ "\"enableSweepSecondChance\":" + settings.enableSweepSecondChance + ","
			+ "\"enableOrbRetest\":" + settings.enableOrbRetest + ","
			+ "\"allowOrbRetestLongs\":" + settings.allowOrbRetestLongs + ","
			+ "\"allowOrbRetestShorts\":" + settings.allowOrbRetestShorts + ","
			+ "\"orbRetestStartMinutes\":" + settings.orbRetestStartMinutes + ","
			+ "\"orbRetestEndMinutes\":" + settings.orbRetestEndMinutes + ","
			+ "\"enableCompressedOrbBreakout\":" + settings.enableCompressedOrbBreakout + ","
			+ "\"skipMidmorningOrbRetest\":" + settings.skipMidmorningOrbRetest + ","
			+ "\"requireHigherTimeframeGuard\":" + settings.requireHigherTimeframeGuard + ","
			+ "\"allowShorts\":" + settings.allowShorts + ","
			+ "\"openingMomentumRangeMinutes\":" + settings.openingMomentumRangeMinutes + ","
			+ "\"openingMomentumMaxHoldBars\":" + settings.openingMomentumMaxHoldBars + ","
			+ "\"openingMomentumVolumeRatio\":" + settings.openingMomentumVolumeRatio + ","
			+ "\"openingMomentumRewardRisk\":" + settings.openingMomentumRewardRisk + ","
			+ "\"earlySweepReclaimTicks\":" + settings.earlySweepReclaimTicks + ","
			+ "\"lateSweepReclaimTicks\":" + settings.lateSweepReclaimTicks + ","
			+ "\"sweepCloseLocation\":" + settings.sweepCloseLocation + ","
			+ "\"lateSweepCloseLocation\":" + settings.lateSweepCloseLocation + ","
			+ "\"minBodyPct\":" + settings.minBodyPct + ","
			+ "\"vwapMinVolumeRatio\":" + settings.vwapMinVolumeRatio + ","
			+ "\"vwapMinTrendSlopeTicks\":" + settings.vwapMinTrendSlopeTicks + ","
			+ "\"vwapMaxDistanceTicks\":" + settings.vwapMaxDistanceTicks + ","
			+ "\"vwapMaxRiskTicks\":" + settings.vwapMaxRiskTicks + ","
			+ "\"meanReversionMinDistanceTicks\":" + settings.meanReversionMinDistanceTicks + ","
			+ "\"meanReversionOversoldRsi\":" + settings.meanReversionOversoldRsi + ","
			+ "\"meanReversionOverboughtRsi\":" + settings.meanReversionOverboughtRsi + ","
			+ "\"minRewardRisk\":" + settings.minRewardRisk + ","
			+ "\"allowCloseMomentumLongs\":" + settings.allowCloseMomentumLongs + ","
			+ "\"allowCloseMomentumShorts\":" + settings.allowCloseMomentumShorts + ","
			+ "\"closeMomentumMinMoveTicks\":" + settings.closeMomentumMinMoveTicks + ","
			+ "\"closeMomentumVolumeRatio\":" + settings.closeMomentumVolumeRatio + ","
			+ "\"closeMomentumRewardRisk\":" + settings.closeMomentumRewardRisk + ","
			+ "\"orbCompressedMaxRiskTicks\":" + settings.orbCompressedMaxRiskTicks + ","
			+ "\"orbRetestMaxRiskTicks\":" + settings.orbRetestMaxRiskTicks + ","
			+ "\"afternoonMinVolumeRatio\":" + settings.afternoonMinVolumeRatio + ","
			+ "\"afternoonMaxRiskTicks\":" + settings.afternoonMaxRiskTicks + ","
			+ "\"afternoonRewardRisk\":" + settings.afternoonRewardRisk + ","
			+ "\"marketIntradayMomentumMinOpenMoveTicks\":" + settings.marketIntradayMomentumMinOpenMoveTicks + ","
			+ "\"marketIntradayMomentumMinLateMoveTicks\":" + settings.marketIntradayMomentumMinLateMoveTicks + ","
			+ "\"marketIntradayMomentumMinVolumeRatio\":" + settings.marketIntradayMomentumMinVolumeRatio + ","
			+ "\"marketIntradayMomentumMaxRiskTicks\":" + settings.marketIntradayMomentumMaxRiskTicks + ","
			+ "\"marketIntradayMomentumRewardRisk\":" + settings.marketIntradayMomentumRewardRisk + ","
			+ "\"allowKeltnerScalpLongs\":" + settings.allowKeltnerScalpLongs + ","
			+ "\"allowKeltnerScalpShorts\":" + settings.allowKeltnerScalpShorts + ","
			+ "\"keltnerAtrMultiplier\":" + settings.keltnerAtrMultiplier + ","
			+ "\"keltnerMinVolumeRatio\":" + settings.keltnerMinVolumeRatio + ","
			+ "\"keltnerMaxRiskTicks\":" + settings.keltnerMaxRiskTicks + ","
			+ "\"keltnerRewardRisk\":" + settings.keltnerRewardRisk + ","
			+ "\"keltnerMinBodyPct\":" + settings.keltnerMinBodyPct + ","
			+ "\"keltnerMinTrendSlopeTicks\":" + settings.keltnerMinTrendSlopeTicks + ","
			+ "\"keltnerMinBandWidthTicks\":" + settings.keltnerMinBandWidthTicks + ","
			+ "\"keltnerMaxHoldBars\":" + settings.keltnerMaxHoldBars + ","
			+ "\"keltnerBucketMinutes\":" + settings.keltnerBucketMinutes + ","
			+ "\"microScalpMinVolumeRatio\":" + settings.microScalpMinVolumeRatio + ","
			+ "\"microScalpMaxRiskTicks\":" + settings.microScalpMaxRiskTicks + ","
			+ "\"microScalpRewardRisk\":" + settings.microScalpRewardRisk + ","
			+ "\"microScalpMinBodyPct\":" + settings.microScalpMinBodyPct + ","
			+ "\"microScalpMinTrendSlopeTicks\":" + settings.microScalpMinTrendSlopeTicks + ","
			+ "\"microScalpMaxHoldBars\":" + settings.microScalpMaxHoldBars + ","
			+ "\"microScalpBucketMinutes\":" + settings.microScalpBucketMinutes + ","
			+ "\"maxInitialRiskTicks\":" + settings.maxInitialRiskTicks + ","
			+ "\"enableAdaptiveExits\":" + settings.enableAdaptiveExits + ","
			+ "\"adaptiveMinVolumeRatio\":" + settings.adaptiveMinVolumeRatio + ","
			+ "\"adaptiveMinBodyPct\":" + settings.adaptiveMinBodyPct + ","
			+ "\"adaptiveTrendTargetBoost\":" + settings.adaptiveTrendTargetBoost + ","
			+ "\"adaptiveVolumeTargetBoost\":" + settings.adaptiveVolumeTargetBoost + ","
			+ "\"adaptiveBodyTargetBoost\":" + settings.adaptiveBodyTargetBoost + ","
			+ "\"adaptiveMaxRewardRisk\":" + settings.adaptiveMaxRewardRisk + ","
			+ "\"enableEarlyLossCut\":" + settings.enableEarlyLossCut + ","
			+ "\"earlyLossCutBars\":" + settings.earlyLossCutBars + ","
			+ "\"earlyLossCutR\":" + settings.earlyLossCutR + ","
			+ "\"earlyLossCutMinFavorableR\":" + settings.earlyLossCutMinFavorableR + ","
			+ "\"openMaeRiskMultiplier\":" + settings.openMaeRiskMultiplier
			+ "}";
	}

	public static String copyBacktestStrategyToLive(String symbols) {
		initializeStore();
		List<String> symbolList = parseSymbols(cleanOrDefault(symbols, "MES,MNQ,NQ,MGC,ES,M2K"));
		if (symbolList.isEmpty()) {
			return "{\"success\":false,\"message\":\"Choose at least one futures symbol.\"}";
		}
		try (Connection conn = DatabaseManager.getConnection()) {
			conn.setAutoCommit(false);
			try {
				for (int index = 0; index < symbolList.size(); index++) {
					copyStrategySlotForSymbol(conn, symbolList.get(index), STRATEGY_SLOT_BACKTEST, STRATEGY_SLOT_LIVE);
				}
				conn.commit();
			} catch (SQLException e) {
				conn.rollback();
				throw e;
			} finally {
				conn.setAutoCommit(true);
			}
			return "{"
				+ "\"success\":true,"
				+ "\"message\":\"Live strategy configuration updated from the backtest strategy configuration.\","
				+ "\"symbols\":" + jsonStringArray(symbolList)
				+ "}";
		} catch (SQLException e) {
			e.printStackTrace();
			return "{\"success\":false,\"message\":\"Failed to copy backtest strategy into live strategy.\"}";
		}
	}

	private static void copyStrategySlotForSymbol(Connection conn, String symbol, String fromSlot, String toSlot) throws SQLException {
		String normalizedSymbol = normalizeSymbol(symbol);
		String fromPrefix = STRATEGY_SLOT_BACKTEST.equals(normalizeStrategySlot(fromSlot))
			? normalizedSymbol + "."
			: normalizeStrategySlot(fromSlot) + "." + normalizedSymbol + ".";
		String toPrefix = STRATEGY_SLOT_BACKTEST.equals(normalizeStrategySlot(toSlot))
			? normalizedSymbol + "."
			: normalizeStrategySlot(toSlot) + "." + normalizedSymbol + ".";
		List<String[]> values = new ArrayList<String[]>();
		try (PreparedStatement select = conn.prepareStatement("SELECT settingKey, settingValue FROM FuturesStrategySettings WHERE settingKey LIKE ?")) {
			select.setString(1, fromPrefix + "%");
			try (ResultSet rs = select.executeQuery()) {
				while (rs.next()) {
					String settingKey = rs.getString("settingKey");
					String unprefixed = settingKey.substring(fromPrefix.length());
					values.add(new String[] {toPrefix + unprefixed, rs.getString("settingValue")});
				}
			}
		}
		if (values.isEmpty()) {
			FuturesStrategySettings settings = loadFuturesStrategySettings(normalizedSymbol, fromSlot);
			saveFuturesStrategySettings(normalizedSymbol, settings);
			try (PreparedStatement select = conn.prepareStatement("SELECT settingKey, settingValue FROM FuturesStrategySettings WHERE settingKey LIKE ?")) {
				select.setString(1, fromPrefix + "%");
				try (ResultSet rs = select.executeQuery()) {
					while (rs.next()) {
						String settingKey = rs.getString("settingKey");
						String unprefixed = settingKey.substring(fromPrefix.length());
						values.add(new String[] {toPrefix + unprefixed, rs.getString("settingValue")});
					}
				}
			}
		}
		try (PreparedStatement delete = conn.prepareStatement("DELETE FROM FuturesStrategySettings WHERE settingKey LIKE ?")) {
			delete.setString(1, toPrefix + "%");
			delete.executeUpdate();
		}
		try (PreparedStatement insert = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) VALUES (?, ?)")) {
			for (int index = 0; index < values.size(); index++) {
				insert.setString(1, values.get(index)[0]);
				insert.setString(2, values.get(index)[1]);
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	public static FuturesRiskSettings loadFuturesRiskSettings() {
		return loadFuturesRiskSettings("MNQ");
	}

	public static FuturesRiskSettings loadFuturesRiskSettings(String symbol) {
		initializeStore();
		String normalizedSymbol = normalizeSymbol(symbol);
		FuturesRiskSettings settings = defaultFuturesRiskSettings(normalizedSymbol);
		String sql = "SELECT settingKey, settingValue FROM FuturesStrategySettings";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				String key = rs.getString("settingKey");
				if (!isSymbolScopedSettingKey(key)) {
					applyFuturesRiskSetting(settings, key, rs.getString("settingValue"));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				String unprefixedKey = unprefixedSymbolSettingKey(normalizedSymbol, rs.getString("settingKey"));
				if (unprefixedKey != null) {
					applyFuturesRiskSetting(settings, unprefixedKey, rs.getString("settingValue"));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		clampFuturesRiskSettings(settings);
		return settings;
	}

	public static boolean saveFuturesRiskSettings(FuturesRiskSettings settings) {
		return saveFuturesRiskSettings("MNQ", settings);
	}

	public static boolean saveFuturesRiskSettings(String symbol, FuturesRiskSettings settings) {
		initializeStore();
		String normalizedSymbol = normalizeSymbol(symbol);
		FuturesRiskSettings safeSettings = settings == null ? defaultFuturesRiskSettings() : settings;
		clampFuturesRiskSettings(safeSettings);
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement("INSERT OR REPLACE INTO FuturesStrategySettings (settingKey, settingValue) VALUES (?, ?)")) {
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "risk.accountSize"), safeSettings.accountSize);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "risk.maxTrailingDrawdown"), safeSettings.maxTrailingDrawdown);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "risk.dailyLossLimit"), safeSettings.dailyLossLimit);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "risk.maxRiskPerTrade"), safeSettings.maxRiskPerTrade);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "risk.maxContracts"), safeSettings.maxContracts);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "risk.commissionPerContract"), safeSettings.commissionPerContract);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "risk.slippageTicks"), safeSettings.slippageTicks);
			saveSetting(pstmt, symbolSettingKey(normalizedSymbol, "risk.profitTarget"), safeSettings.profitTarget);
			pstmt.executeBatch();
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static String getFuturesRiskSettingsJson() {
		return getFuturesRiskSettingsJson("MNQ");
	}

	public static String getFuturesRiskSettingsJson(String symbol) {
		String normalizedSymbol = normalizeSymbol(symbol);
		FuturesRiskSettings settings = loadFuturesRiskSettings(normalizedSymbol);
		return "{"
			+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
			+ "\"availableSymbols\":" + jsonStringArray(supportedInstrumentSymbols()) + ","
			+ "\"accountSize\":" + settings.accountSize + ","
			+ "\"maxTrailingDrawdown\":" + settings.maxTrailingDrawdown + ","
			+ "\"dailyLossLimit\":" + settings.dailyLossLimit + ","
			+ "\"maxRiskPerTrade\":" + settings.maxRiskPerTrade + ","
			+ "\"maxContracts\":" + settings.maxContracts + ","
			+ "\"commissionPerContract\":" + settings.commissionPerContract + ","
			+ "\"slippageTicks\":" + settings.slippageTicks + ","
			+ "\"profitTarget\":" + settings.profitTarget
			+ "}";
	}

	private static FuturesStrategySettings defaultFuturesStrategySettings() {
		return new FuturesStrategySettings();
	}

	private static FuturesRiskSettings defaultFuturesRiskSettings() {
		return defaultFuturesRiskSettings("MNQ");
	}

	private static FuturesRiskSettings defaultFuturesRiskSettings(String symbol) {
		FuturesRiskSettings settings = new FuturesRiskSettings();
		settings.maxContracts = topstepMaxContractsForSymbol(symbol);
		return settings;
	}

	private static String symbolSettingKey(String symbol, String key) {
		return normalizeSymbol(symbol) + "." + key;
	}

	private static String slotSymbolSettingKey(String slot, String symbol, String key) {
		String normalizedSlot = normalizeStrategySlot(slot);
		String symbolKey = symbolSettingKey(symbol, key);
		return STRATEGY_SLOT_BACKTEST.equals(normalizedSlot) ? symbolKey : normalizedSlot + "." + symbolKey;
	}

	private static String normalizeStrategySlot(String slot) {
		String normalized = slot == null ? "" : slot.trim().toUpperCase();
		return STRATEGY_SLOT_LIVE.equals(normalized) ? STRATEGY_SLOT_LIVE : STRATEGY_SLOT_BACKTEST;
	}

	private static boolean isSymbolScopedSettingKey(String key) {
		if (key == null) {
			return false;
		}
		int dot = key.indexOf(".");
		if (dot <= 0) {
			return false;
		}
		String prefix = key.substring(0, dot);
		return supportedInstrumentSymbols().contains(prefix);
	}

	private static String unprefixedSymbolSettingKey(String symbol, String key) {
		if (key == null) {
			return null;
		}
		String prefix = normalizeSymbol(symbol) + ".";
		return key.startsWith(prefix) ? key.substring(prefix.length()) : null;
	}

	private static String unprefixedSlotSymbolSettingKey(String slot, String symbol, String key) {
		if (key == null) {
			return null;
		}
		String prefix = normalizeStrategySlot(slot) + "." + normalizeSymbol(symbol) + ".";
		return key.startsWith(prefix) ? key.substring(prefix.length()) : null;
	}

	private static void saveSetting(PreparedStatement pstmt, String key, boolean value) throws SQLException {
		saveSetting(pstmt, key, String.valueOf(value));
	}

	private static void saveSetting(PreparedStatement pstmt, String key, int value) throws SQLException {
		saveSetting(pstmt, key, String.valueOf(value));
	}

	private static void saveSetting(PreparedStatement pstmt, String key, double value) throws SQLException {
		saveSetting(pstmt, key, String.valueOf(value));
	}

	private static void saveSetting(PreparedStatement pstmt, String key, String value) throws SQLException {
		pstmt.setString(1, key);
		pstmt.setString(2, value);
		pstmt.addBatch();
	}

	private static String toggleJson(StrategyToggle toggle) {
		return "{\"enabled\":" + toggle.enabled + ",\"maxTradesPerDay\":" + toggle.maxTradesPerDay + "}";
	}

	private static void applyFuturesSetting(FuturesStrategySettings settings, String key, String value) {
		if (key == null) {
			return;
		}
		if ("orb.enabled".equals(key)) settings.orb.enabled = parseBoolean(value, settings.orb.enabled);
		else if ("orb.maxTradesPerDay".equals(key)) settings.orb.maxTradesPerDay = parseInt(value, settings.orb.maxTradesPerDay);
		else if ("openingMomentum.enabled".equals(key)) settings.openingMomentum.enabled = parseBoolean(value, settings.openingMomentum.enabled);
		else if ("openingMomentum.maxTradesPerDay".equals(key)) settings.openingMomentum.maxTradesPerDay = parseInt(value, settings.openingMomentum.maxTradesPerDay);
		else if ("sweep.enabled".equals(key)) settings.sweep.enabled = parseBoolean(value, settings.sweep.enabled);
		else if ("sweep.maxTradesPerDay".equals(key)) settings.sweep.maxTradesPerDay = parseInt(value, settings.sweep.maxTradesPerDay);
		else if ("vwapPullback.enabled".equals(key)) settings.vwapPullback.enabled = parseBoolean(value, settings.vwapPullback.enabled);
		else if ("vwapPullback.maxTradesPerDay".equals(key)) settings.vwapPullback.maxTradesPerDay = parseInt(value, settings.vwapPullback.maxTradesPerDay);
		else if ("vwapMeanReversion.enabled".equals(key)) settings.vwapMeanReversion.enabled = parseBoolean(value, settings.vwapMeanReversion.enabled);
		else if ("vwapMeanReversion.maxTradesPerDay".equals(key)) settings.vwapMeanReversion.maxTradesPerDay = parseInt(value, settings.vwapMeanReversion.maxTradesPerDay);
		else if ("fvg.enabled".equals(key)) settings.fvg.enabled = parseBoolean(value, settings.fvg.enabled);
		else if ("fvg.maxTradesPerDay".equals(key)) settings.fvg.maxTradesPerDay = parseInt(value, settings.fvg.maxTradesPerDay);
		else if ("closeMomentum.enabled".equals(key)) settings.closeMomentum.enabled = parseBoolean(value, settings.closeMomentum.enabled);
		else if ("closeMomentum.maxTradesPerDay".equals(key)) settings.closeMomentum.maxTradesPerDay = parseInt(value, settings.closeMomentum.maxTradesPerDay);
		else if ("afternoonContinuation.enabled".equals(key)) settings.afternoonContinuation.enabled = parseBoolean(value, settings.afternoonContinuation.enabled);
		else if ("afternoonContinuation.maxTradesPerDay".equals(key)) settings.afternoonContinuation.maxTradesPerDay = parseInt(value, settings.afternoonContinuation.maxTradesPerDay);
		else if ("marketIntradayMomentum.enabled".equals(key)) settings.marketIntradayMomentum.enabled = parseBoolean(value, settings.marketIntradayMomentum.enabled);
		else if ("marketIntradayMomentum.maxTradesPerDay".equals(key)) settings.marketIntradayMomentum.maxTradesPerDay = parseInt(value, settings.marketIntradayMomentum.maxTradesPerDay);
		else if ("keltnerScalp.enabled".equals(key)) settings.keltnerScalp.enabled = parseBoolean(value, settings.keltnerScalp.enabled);
		else if ("keltnerScalp.maxTradesPerDay".equals(key)) settings.keltnerScalp.maxTradesPerDay = parseInt(value, settings.keltnerScalp.maxTradesPerDay);
		else if ("keltnerReversion.enabled".equals(key)) settings.keltnerReversion.enabled = parseBoolean(value, settings.keltnerReversion.enabled);
		else if ("keltnerReversion.maxTradesPerDay".equals(key)) settings.keltnerReversion.maxTradesPerDay = parseInt(value, settings.keltnerReversion.maxTradesPerDay);
		else if ("microScalp.enabled".equals(key)) settings.microScalp.enabled = parseBoolean(value, settings.microScalp.enabled);
		else if ("microScalp.maxTradesPerDay".equals(key)) settings.microScalp.maxTradesPerDay = parseInt(value, settings.microScalp.maxTradesPerDay);
		else if ("enableEarlySweep".equals(key)) settings.enableEarlySweep = parseBoolean(value, settings.enableEarlySweep);
		else if ("enableLateSweep".equals(key)) settings.enableLateSweep = parseBoolean(value, settings.enableLateSweep);
		else if ("enableSweepSecondChance".equals(key)) settings.enableSweepSecondChance = parseBoolean(value, settings.enableSweepSecondChance);
		else if ("enableOrbRetest".equals(key)) settings.enableOrbRetest = parseBoolean(value, settings.enableOrbRetest);
		else if ("allowOrbRetestLongs".equals(key)) settings.allowOrbRetestLongs = parseBoolean(value, settings.allowOrbRetestLongs);
		else if ("allowOrbRetestShorts".equals(key)) settings.allowOrbRetestShorts = parseBoolean(value, settings.allowOrbRetestShorts);
		else if ("orbRetestStartMinutes".equals(key)) settings.orbRetestStartMinutes = parseInt(value, settings.orbRetestStartMinutes);
		else if ("orbRetestEndMinutes".equals(key)) settings.orbRetestEndMinutes = parseInt(value, settings.orbRetestEndMinutes);
		else if ("enableCompressedOrbBreakout".equals(key)) settings.enableCompressedOrbBreakout = parseBoolean(value, settings.enableCompressedOrbBreakout);
		else if ("skipMidmorningOrbRetest".equals(key)) settings.skipMidmorningOrbRetest = parseBoolean(value, settings.skipMidmorningOrbRetest);
		else if ("requireHigherTimeframeGuard".equals(key)) settings.requireHigherTimeframeGuard = parseBoolean(value, settings.requireHigherTimeframeGuard);
		else if ("allowShorts".equals(key)) settings.allowShorts = parseBoolean(value, settings.allowShorts);
		else if ("openingMomentumRangeMinutes".equals(key)) settings.openingMomentumRangeMinutes = parseInt(value, settings.openingMomentumRangeMinutes);
		else if ("openingMomentumMaxHoldBars".equals(key)) settings.openingMomentumMaxHoldBars = parseInt(value, settings.openingMomentumMaxHoldBars);
		else if ("openingMomentumVolumeRatio".equals(key)) settings.openingMomentumVolumeRatio = parseDouble(value, settings.openingMomentumVolumeRatio);
		else if ("openingMomentumRewardRisk".equals(key)) settings.openingMomentumRewardRisk = parseDouble(value, settings.openingMomentumRewardRisk);
		else if ("earlySweepReclaimTicks".equals(key)) settings.earlySweepReclaimTicks = parseDouble(value, settings.earlySweepReclaimTicks);
		else if ("lateSweepReclaimTicks".equals(key)) settings.lateSweepReclaimTicks = parseDouble(value, settings.lateSweepReclaimTicks);
		else if ("sweepCloseLocation".equals(key)) settings.sweepCloseLocation = parseDouble(value, settings.sweepCloseLocation);
		else if ("lateSweepCloseLocation".equals(key)) settings.lateSweepCloseLocation = parseDouble(value, settings.lateSweepCloseLocation);
		else if ("minBodyPct".equals(key)) settings.minBodyPct = parseDouble(value, settings.minBodyPct);
		else if ("vwapMinVolumeRatio".equals(key)) settings.vwapMinVolumeRatio = parseDouble(value, settings.vwapMinVolumeRatio);
		else if ("vwapMinTrendSlopeTicks".equals(key)) settings.vwapMinTrendSlopeTicks = parseDouble(value, settings.vwapMinTrendSlopeTicks);
		else if ("vwapMaxDistanceTicks".equals(key)) settings.vwapMaxDistanceTicks = parseDouble(value, settings.vwapMaxDistanceTicks);
		else if ("vwapMaxRiskTicks".equals(key)) settings.vwapMaxRiskTicks = parseDouble(value, settings.vwapMaxRiskTicks);
		else if ("meanReversionMinDistanceTicks".equals(key)) settings.meanReversionMinDistanceTicks = parseDouble(value, settings.meanReversionMinDistanceTicks);
		else if ("meanReversionOversoldRsi".equals(key)) settings.meanReversionOversoldRsi = parseDouble(value, settings.meanReversionOversoldRsi);
		else if ("meanReversionOverboughtRsi".equals(key)) settings.meanReversionOverboughtRsi = parseDouble(value, settings.meanReversionOverboughtRsi);
		else if ("minRewardRisk".equals(key)) settings.minRewardRisk = parseDouble(value, settings.minRewardRisk);
		else if ("allowCloseMomentumLongs".equals(key)) settings.allowCloseMomentumLongs = parseBoolean(value, settings.allowCloseMomentumLongs);
		else if ("allowCloseMomentumShorts".equals(key)) settings.allowCloseMomentumShorts = parseBoolean(value, settings.allowCloseMomentumShorts);
		else if ("closeMomentumMinMoveTicks".equals(key)) settings.closeMomentumMinMoveTicks = parseDouble(value, settings.closeMomentumMinMoveTicks);
		else if ("closeMomentumVolumeRatio".equals(key)) settings.closeMomentumVolumeRatio = parseDouble(value, settings.closeMomentumVolumeRatio);
		else if ("closeMomentumRewardRisk".equals(key)) settings.closeMomentumRewardRisk = parseDouble(value, settings.closeMomentumRewardRisk);
		else if ("orbCompressedMaxRiskTicks".equals(key)) settings.orbCompressedMaxRiskTicks = parseDouble(value, settings.orbCompressedMaxRiskTicks);
		else if ("orbRetestMaxRiskTicks".equals(key)) settings.orbRetestMaxRiskTicks = parseDouble(value, settings.orbRetestMaxRiskTicks);
		else if ("afternoonMinVolumeRatio".equals(key)) settings.afternoonMinVolumeRatio = parseDouble(value, settings.afternoonMinVolumeRatio);
		else if ("afternoonMaxRiskTicks".equals(key)) settings.afternoonMaxRiskTicks = parseDouble(value, settings.afternoonMaxRiskTicks);
		else if ("afternoonRewardRisk".equals(key)) settings.afternoonRewardRisk = parseDouble(value, settings.afternoonRewardRisk);
		else if ("marketIntradayMomentumMinOpenMoveTicks".equals(key)) settings.marketIntradayMomentumMinOpenMoveTicks = parseDouble(value, settings.marketIntradayMomentumMinOpenMoveTicks);
		else if ("marketIntradayMomentumMinLateMoveTicks".equals(key)) settings.marketIntradayMomentumMinLateMoveTicks = parseDouble(value, settings.marketIntradayMomentumMinLateMoveTicks);
		else if ("marketIntradayMomentumMinVolumeRatio".equals(key)) settings.marketIntradayMomentumMinVolumeRatio = parseDouble(value, settings.marketIntradayMomentumMinVolumeRatio);
		else if ("marketIntradayMomentumMaxRiskTicks".equals(key)) settings.marketIntradayMomentumMaxRiskTicks = parseDouble(value, settings.marketIntradayMomentumMaxRiskTicks);
		else if ("marketIntradayMomentumRewardRisk".equals(key)) settings.marketIntradayMomentumRewardRisk = parseDouble(value, settings.marketIntradayMomentumRewardRisk);
		else if ("allowKeltnerScalpLongs".equals(key)) settings.allowKeltnerScalpLongs = parseBoolean(value, settings.allowKeltnerScalpLongs);
		else if ("allowKeltnerScalpShorts".equals(key)) settings.allowKeltnerScalpShorts = parseBoolean(value, settings.allowKeltnerScalpShorts);
		else if ("keltnerAtrMultiplier".equals(key)) settings.keltnerAtrMultiplier = parseDouble(value, settings.keltnerAtrMultiplier);
		else if ("keltnerMinVolumeRatio".equals(key)) settings.keltnerMinVolumeRatio = parseDouble(value, settings.keltnerMinVolumeRatio);
		else if ("keltnerMaxRiskTicks".equals(key)) settings.keltnerMaxRiskTicks = parseDouble(value, settings.keltnerMaxRiskTicks);
		else if ("keltnerRewardRisk".equals(key)) settings.keltnerRewardRisk = parseDouble(value, settings.keltnerRewardRisk);
		else if ("keltnerMinBodyPct".equals(key)) settings.keltnerMinBodyPct = parseDouble(value, settings.keltnerMinBodyPct);
		else if ("keltnerMinTrendSlopeTicks".equals(key)) settings.keltnerMinTrendSlopeTicks = parseDouble(value, settings.keltnerMinTrendSlopeTicks);
		else if ("keltnerMinBandWidthTicks".equals(key)) settings.keltnerMinBandWidthTicks = parseDouble(value, settings.keltnerMinBandWidthTicks);
		else if ("keltnerMaxHoldBars".equals(key)) settings.keltnerMaxHoldBars = parseInt(value, settings.keltnerMaxHoldBars);
		else if ("keltnerBucketMinutes".equals(key)) settings.keltnerBucketMinutes = parseInt(value, settings.keltnerBucketMinutes);
		else if ("microScalpMinVolumeRatio".equals(key)) settings.microScalpMinVolumeRatio = parseDouble(value, settings.microScalpMinVolumeRatio);
		else if ("microScalpMaxRiskTicks".equals(key)) settings.microScalpMaxRiskTicks = parseDouble(value, settings.microScalpMaxRiskTicks);
		else if ("microScalpRewardRisk".equals(key)) settings.microScalpRewardRisk = parseDouble(value, settings.microScalpRewardRisk);
		else if ("microScalpMinBodyPct".equals(key)) settings.microScalpMinBodyPct = parseDouble(value, settings.microScalpMinBodyPct);
		else if ("microScalpMinTrendSlopeTicks".equals(key)) settings.microScalpMinTrendSlopeTicks = parseDouble(value, settings.microScalpMinTrendSlopeTicks);
		else if ("microScalpMaxHoldBars".equals(key)) settings.microScalpMaxHoldBars = parseInt(value, settings.microScalpMaxHoldBars);
		else if ("microScalpBucketMinutes".equals(key)) settings.microScalpBucketMinutes = parseInt(value, settings.microScalpBucketMinutes);
		else if ("maxInitialRiskTicks".equals(key)) settings.maxInitialRiskTicks = parseDouble(value, settings.maxInitialRiskTicks);
		else if ("enableAdaptiveExits".equals(key)) settings.enableAdaptiveExits = parseBoolean(value, settings.enableAdaptiveExits);
		else if ("adaptiveMinVolumeRatio".equals(key)) settings.adaptiveMinVolumeRatio = parseDouble(value, settings.adaptiveMinVolumeRatio);
		else if ("adaptiveMinBodyPct".equals(key)) settings.adaptiveMinBodyPct = parseDouble(value, settings.adaptiveMinBodyPct);
		else if ("adaptiveTrendTargetBoost".equals(key)) settings.adaptiveTrendTargetBoost = parseDouble(value, settings.adaptiveTrendTargetBoost);
		else if ("adaptiveVolumeTargetBoost".equals(key)) settings.adaptiveVolumeTargetBoost = parseDouble(value, settings.adaptiveVolumeTargetBoost);
		else if ("adaptiveBodyTargetBoost".equals(key)) settings.adaptiveBodyTargetBoost = parseDouble(value, settings.adaptiveBodyTargetBoost);
		else if ("adaptiveMaxRewardRisk".equals(key)) settings.adaptiveMaxRewardRisk = parseDouble(value, settings.adaptiveMaxRewardRisk);
		else if ("enableEarlyLossCut".equals(key)) settings.enableEarlyLossCut = parseBoolean(value, settings.enableEarlyLossCut);
		else if ("earlyLossCutBars".equals(key)) settings.earlyLossCutBars = parseInt(value, settings.earlyLossCutBars);
		else if ("earlyLossCutR".equals(key)) settings.earlyLossCutR = parseDouble(value, settings.earlyLossCutR);
		else if ("earlyLossCutMinFavorableR".equals(key)) settings.earlyLossCutMinFavorableR = parseDouble(value, settings.earlyLossCutMinFavorableR);
		else if ("openMaeRiskMultiplier".equals(key)) settings.openMaeRiskMultiplier = parseDouble(value, settings.openMaeRiskMultiplier);
	}

	private static void applyFuturesRiskSetting(FuturesRiskSettings settings, String key, String value) {
		if (key == null) {
			return;
		}
		if ("risk.accountSize".equals(key)) settings.accountSize = parseDouble(value, settings.accountSize);
		else if ("risk.maxTrailingDrawdown".equals(key)) settings.maxTrailingDrawdown = parseDouble(value, settings.maxTrailingDrawdown);
		else if ("risk.dailyLossLimit".equals(key)) settings.dailyLossLimit = parseDouble(value, settings.dailyLossLimit);
		else if ("risk.maxRiskPerTrade".equals(key)) settings.maxRiskPerTrade = parseDouble(value, settings.maxRiskPerTrade);
		else if ("risk.maxContracts".equals(key)) settings.maxContracts = parseInt(value, settings.maxContracts);
		else if ("risk.commissionPerContract".equals(key)) settings.commissionPerContract = parseDouble(value, settings.commissionPerContract);
		else if ("risk.slippageTicks".equals(key)) settings.slippageTicks = parseDouble(value, settings.slippageTicks);
		else if ("risk.profitTarget".equals(key)) settings.profitTarget = parseDouble(value, settings.profitTarget);
	}

	private static void clampFuturesStrategySettings(FuturesStrategySettings settings) {
		settings.orb.maxTradesPerDay = boundedInt(settings.orb.maxTradesPerDay, 1, 0, 5);
		settings.openingMomentum.maxTradesPerDay = boundedInt(settings.openingMomentum.maxTradesPerDay, 2, 0, 5);
		settings.sweep.maxTradesPerDay = boundedInt(settings.sweep.maxTradesPerDay, 2, 0, 5);
		settings.vwapPullback.maxTradesPerDay = boundedInt(settings.vwapPullback.maxTradesPerDay, 2, 0, 5);
		settings.vwapMeanReversion.maxTradesPerDay = boundedInt(settings.vwapMeanReversion.maxTradesPerDay, 2, 0, 5);
		settings.fvg.maxTradesPerDay = boundedInt(settings.fvg.maxTradesPerDay, 1, 0, 5);
		settings.closeMomentum.maxTradesPerDay = boundedInt(settings.closeMomentum.maxTradesPerDay, 1, 0, 3);
		settings.afternoonContinuation.maxTradesPerDay = boundedInt(settings.afternoonContinuation.maxTradesPerDay, 2, 0, 5);
		settings.marketIntradayMomentum.maxTradesPerDay = boundedInt(settings.marketIntradayMomentum.maxTradesPerDay, 1, 0, 2);
		settings.keltnerScalp.maxTradesPerDay = boundedInt(settings.keltnerScalp.maxTradesPerDay, 8, 0, 20);
		settings.keltnerReversion.maxTradesPerDay = boundedInt(settings.keltnerReversion.maxTradesPerDay, 6, 0, 20);
		settings.microScalp.maxTradesPerDay = boundedInt(settings.microScalp.maxTradesPerDay, 6, 0, 20);
		settings.orbRetestStartMinutes = boundedInt(settings.orbRetestStartMinutes, 0, 0, 150);
		settings.orbRetestEndMinutes = boundedInt(settings.orbRetestEndMinutes, 135, 0, 150);
		if (settings.orbRetestEndMinutes < settings.orbRetestStartMinutes) {
			settings.orbRetestEndMinutes = settings.orbRetestStartMinutes;
		}
		settings.openingMomentumRangeMinutes = boundedInt(settings.openingMomentumRangeMinutes, 10, 5, 30);
		settings.openingMomentumMaxHoldBars = boundedInt(settings.openingMomentumMaxHoldBars, 120, 15, 180);
		settings.openingMomentumVolumeRatio = clamp(settings.openingMomentumVolumeRatio, 0.25, 2.0);
		settings.openingMomentumRewardRisk = clamp(settings.openingMomentumRewardRisk, 0.5, 2.0);
		settings.earlySweepReclaimTicks = clamp(settings.earlySweepReclaimTicks, 1.0, 40.0);
		settings.lateSweepReclaimTicks = clamp(settings.lateSweepReclaimTicks, 1.0, 40.0);
		settings.sweepCloseLocation = clamp(settings.sweepCloseLocation, 0.1, 0.95);
		settings.lateSweepCloseLocation = clamp(settings.lateSweepCloseLocation, 0.1, 0.95);
		settings.minBodyPct = clamp(settings.minBodyPct, 0.0, 90.0);
		settings.vwapMinVolumeRatio = clamp(settings.vwapMinVolumeRatio, 0.0, 4.0);
		settings.vwapMinTrendSlopeTicks = clamp(settings.vwapMinTrendSlopeTicks, 0.0, 60.0);
		settings.vwapMaxDistanceTicks = clamp(settings.vwapMaxDistanceTicks, 4.0, 160.0);
		settings.vwapMaxRiskTicks = clamp(settings.vwapMaxRiskTicks, 4.0, 160.0);
		settings.meanReversionMinDistanceTicks = clamp(settings.meanReversionMinDistanceTicks, 4.0, 180.0);
		settings.meanReversionOversoldRsi = clamp(settings.meanReversionOversoldRsi, 5.0, 45.0);
		settings.meanReversionOverboughtRsi = clamp(settings.meanReversionOverboughtRsi, 55.0, 95.0);
		settings.minRewardRisk = clamp(settings.minRewardRisk, 0.75, 3.0);
		settings.closeMomentumMinMoveTicks = clamp(settings.closeMomentumMinMoveTicks, 4.0, 180.0);
		settings.closeMomentumVolumeRatio = clamp(settings.closeMomentumVolumeRatio, 0.0, 4.0);
		settings.closeMomentumRewardRisk = clamp(settings.closeMomentumRewardRisk, 0.5, 2.0);
		settings.orbCompressedMaxRiskTicks = clamp(settings.orbCompressedMaxRiskTicks, 4.0, 220.0);
		settings.orbRetestMaxRiskTicks = clamp(settings.orbRetestMaxRiskTicks, 4.0, 220.0);
		settings.afternoonMinVolumeRatio = clamp(settings.afternoonMinVolumeRatio, 0.0, 4.0);
		settings.afternoonMaxRiskTicks = clamp(settings.afternoonMaxRiskTicks, 4.0, 180.0);
		settings.afternoonRewardRisk = clamp(settings.afternoonRewardRisk, 0.5, 2.0);
		settings.marketIntradayMomentumMinOpenMoveTicks = clamp(settings.marketIntradayMomentumMinOpenMoveTicks, 2.0, 180.0);
		settings.marketIntradayMomentumMinLateMoveTicks = clamp(settings.marketIntradayMomentumMinLateMoveTicks, 2.0, 180.0);
		settings.marketIntradayMomentumMinVolumeRatio = clamp(settings.marketIntradayMomentumMinVolumeRatio, 0.0, 4.0);
		settings.marketIntradayMomentumMaxRiskTicks = clamp(settings.marketIntradayMomentumMaxRiskTicks, 4.0, 180.0);
		settings.marketIntradayMomentumRewardRisk = clamp(settings.marketIntradayMomentumRewardRisk, 0.5, 2.0);
		settings.keltnerAtrMultiplier = clamp(settings.keltnerAtrMultiplier, 0.7, 3.0);
		settings.keltnerMinVolumeRatio = clamp(settings.keltnerMinVolumeRatio, 0.0, 4.0);
		settings.keltnerMaxRiskTicks = clamp(settings.keltnerMaxRiskTicks, 3.0, 80.0);
		settings.keltnerRewardRisk = clamp(settings.keltnerRewardRisk, 0.35, 2.5);
		settings.keltnerMinBodyPct = clamp(settings.keltnerMinBodyPct, 0.0, 90.0);
		settings.keltnerMinTrendSlopeTicks = clamp(settings.keltnerMinTrendSlopeTicks, 0.0, 60.0);
		settings.keltnerMinBandWidthTicks = clamp(settings.keltnerMinBandWidthTicks, 0.0, 120.0);
		settings.keltnerMaxHoldBars = boundedInt(settings.keltnerMaxHoldBars, 10, 3, 60);
		settings.keltnerBucketMinutes = boundedInt(settings.keltnerBucketMinutes, 12, 3, 60);
		settings.microScalpMinVolumeRatio = clamp(settings.microScalpMinVolumeRatio, 0.0, 4.0);
		settings.microScalpMaxRiskTicks = clamp(settings.microScalpMaxRiskTicks, 3.0, 60.0);
		settings.microScalpRewardRisk = clamp(settings.microScalpRewardRisk, 0.35, 2.0);
		settings.microScalpMinBodyPct = clamp(settings.microScalpMinBodyPct, 0.0, 90.0);
		settings.microScalpMinTrendSlopeTicks = clamp(settings.microScalpMinTrendSlopeTicks, 0.0, 40.0);
		settings.microScalpMaxHoldBars = boundedInt(settings.microScalpMaxHoldBars, 10, 3, 45);
		settings.microScalpBucketMinutes = boundedInt(settings.microScalpBucketMinutes, 20, 5, 60);
		settings.maxInitialRiskTicks = clamp(settings.maxInitialRiskTicks, 8.0, 220.0);
		settings.adaptiveMinVolumeRatio = clamp(settings.adaptiveMinVolumeRatio, 0.0, 4.0);
		settings.adaptiveMinBodyPct = clamp(settings.adaptiveMinBodyPct, 0.0, 95.0);
		settings.adaptiveTrendTargetBoost = clamp(settings.adaptiveTrendTargetBoost, 0.0, 2.0);
		settings.adaptiveVolumeTargetBoost = clamp(settings.adaptiveVolumeTargetBoost, 0.0, 2.0);
		settings.adaptiveBodyTargetBoost = clamp(settings.adaptiveBodyTargetBoost, 0.0, 2.0);
		settings.adaptiveMaxRewardRisk = clamp(settings.adaptiveMaxRewardRisk, 0.75, 5.0);
		settings.earlyLossCutBars = boundedInt(settings.earlyLossCutBars, 18, 3, 120);
		settings.earlyLossCutR = clamp(settings.earlyLossCutR, 0.1, 1.25);
		settings.earlyLossCutMinFavorableR = clamp(settings.earlyLossCutMinFavorableR, 0.0, 1.0);
		settings.openMaeRiskMultiplier = clamp(settings.openMaeRiskMultiplier, 1.0, 5.0);
	}

	private static void clampFuturesRiskSettings(FuturesRiskSettings settings) {
		settings.accountSize = clamp(settings.accountSize, 1000.0, 1000000.0);
		settings.maxTrailingDrawdown = clamp(settings.maxTrailingDrawdown, 100.0, 100000.0);
		settings.dailyLossLimit = clamp(settings.dailyLossLimit, 50.0, 100000.0);
		settings.maxRiskPerTrade = clamp(settings.maxRiskPerTrade, 25.0, 50000.0);
		settings.maxContracts = boundedInt(settings.maxContracts, 12, 1, 50);
		settings.commissionPerContract = clamp(settings.commissionPerContract, 0.0, 100.0);
		settings.slippageTicks = clamp(settings.slippageTicks, 0.0, 20.0);
		settings.profitTarget = Math.max(0.0, settings.profitTarget);
	}

	public static String getBacktestsJson() {
		initializeStore();
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT * FROM FuturesBacktests ORDER BY futuresBacktestID DESC";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				if (json.length() > 1) {
					json.append(",");
				}
				json.append("{")
					.append("\"id\":").append(rs.getInt("futuresBacktestID")).append(",")
					.append("\"symbol\":").append(jsonString(rs.getString("symbol"))).append(",")
					.append("\"contractName\":").append(jsonString(rs.getString("contractName"))).append(",")
					.append("\"startDate\":").append(jsonString(rs.getString("startDate"))).append(",")
					.append("\"endDate\":").append(jsonString(rs.getString("endDate"))).append(",")
					.append("\"startingBalance\":").append(rs.getDouble("startingBalance")).append(",")
					.append("\"endingBalance\":").append(rs.getDouble("endingBalance")).append(",")
					.append("\"totalProfit\":").append(rs.getDouble("totalProfit")).append(",")
					.append("\"returnPct\":").append(rs.getDouble("returnPct")).append(",")
					.append("\"winRate\":").append(rs.getDouble("winRate")).append(",")
					.append("\"trades\":").append(rs.getInt("numTrades")).append(",")
					.append("\"profitFactor\":").append(rs.getDouble("profitFactor")).append(",")
					.append("\"maxDrawdownPct\":").append(rs.getDouble("maxDrawdownPct")).append(",")
					.append("\"ruleViolation\":").append(rs.getInt("ruleViolation") == 1).append(",")
					.append("\"ruleMessage\":").append(jsonString(rs.getString("ruleMessage"))).append(",")
					.append("\"dataSource\":").append(jsonString(rs.getString("dataSource"))).append(",")
					.append("\"createdAt\":").append(jsonString(rs.getString("createdAt")))
					.append("}");
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	public static String getPortfolioBacktestsJson() {
		initializeStore();
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT * FROM FuturesPortfolioBacktests ORDER BY portfolioBacktestID DESC";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				if (json.length() > 1) {
					json.append(",");
				}
				json.append("{")
					.append("\"id\":").append(rs.getInt("portfolioBacktestID")).append(",")
					.append("\"fundedProfile\":").append(jsonString(rs.getString("fundedProfile"))).append(",")
					.append("\"symbols\":").append(jsonString(rs.getString("symbols"))).append(",")
					.append("\"startDate\":").append(jsonString(rs.getString("startDate"))).append(",")
					.append("\"endDate\":").append(jsonString(rs.getString("endDate"))).append(",")
					.append("\"startingBalance\":").append(rs.getDouble("startingBalance")).append(",")
					.append("\"endingBalance\":").append(rs.getDouble("endingBalance")).append(",")
					.append("\"totalProfit\":").append(rs.getDouble("totalProfit")).append(",")
					.append("\"returnPct\":").append(rs.getDouble("returnPct")).append(",")
					.append("\"winRate\":").append(rs.getDouble("winRate")).append(",")
					.append("\"trades\":").append(rs.getInt("numTrades")).append(",")
					.append("\"profitFactor\":").append(rs.getDouble("profitFactor")).append(",")
					.append("\"maxDrawdownPct\":").append(rs.getDouble("maxDrawdownPct")).append(",")
					.append("\"maxConcurrentPositions\":").append(rs.getInt("maxConcurrentPositions")).append(",")
					.append("\"maxConcurrentContracts\":").append(rs.getInt("maxConcurrentContracts")).append(",")
					.append("\"maxConcurrentMiniUnits\":").append(rs.getDouble("maxConcurrentMiniUnits")).append(",")
					.append("\"maxAggregateMiniUnits\":").append(rs.getDouble("maxAggregateMiniUnits")).append(",")
					.append("\"maxNotionalExposure\":").append(rs.getDouble("maxNotionalExposure")).append(",")
					.append("\"maxIntradayLoss\":").append(rs.getDouble("maxIntradayLoss")).append(",")
					.append("\"maxAggregateMae\":").append(rs.getDouble("maxAggregateMae")).append(",")
					.append("\"dailyLossBreaches\":").append(rs.getInt("dailyLossBreaches")).append(",")
					.append("\"trailingDrawdownBreaches\":").append(rs.getInt("trailingDrawdownBreaches")).append(",")
					.append("\"maeBreaches\":").append(rs.getInt("maeBreaches")).append(",")
					.append("\"overlapRejections\":").append(rs.getInt("overlapRejections")).append(",")
					.append("\"exposureRejections\":").append(rs.getInt("exposureRejections")).append(",")
					.append("\"riskRejections\":").append(rs.getInt("riskRejections")).append(",")
					.append("\"ruleViolation\":").append(rs.getInt("ruleViolation") == 1).append(",")
					.append("\"ruleMessage\":").append(jsonString(rs.getString("ruleMessage"))).append(",")
					.append("\"dataSource\":").append(jsonString(rs.getString("dataSource"))).append(",")
					.append("\"createdAt\":").append(jsonString(rs.getString("createdAt")))
					.append("}");
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	public static String getBacktestTradesJson(int backtestId) {
		initializeStore();
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT * FROM FuturesTrades WHERE futuresBacktestID = ? ORDER BY futuresTradeID ASC";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, backtestId);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					if (json.length() > 1) {
						json.append(",");
					}
					json.append("{")
						.append("\"id\":").append(rs.getInt("futuresTradeID")).append(",")
						.append("\"symbol\":").append(jsonString(rs.getString("symbol"))).append(",")
						.append("\"strategyCode\":").append(jsonString(rs.getString("strategyCode"))).append(",")
						.append("\"strategyName\":").append(jsonString(rs.getString("strategyName"))).append(",")
						.append("\"side\":").append(jsonString(rs.getString("side"))).append(",")
						.append("\"contracts\":").append(rs.getInt("contracts")).append(",")
						.append("\"entry\":").append(rs.getDouble("entryPrice")).append(",")
						.append("\"exit\":").append(rs.getDouble("exitPrice")).append(",")
						.append("\"stop\":").append(rs.getDouble("stopPrice")).append(",")
						.append("\"target\":").append(rs.getDouble("targetPrice")).append(",")
						.append("\"openedAt\":").append(jsonString(rs.getString("openedAt"))).append(",")
						.append("\"closedAt\":").append(jsonString(rs.getString("closedAt"))).append(",")
						.append("\"pnl\":").append(rs.getDouble("pnl")).append(",")
						.append("\"mfe\":").append(rs.getDouble("mfe")).append(",")
						.append("\"mae\":").append(rs.getDouble("mae")).append(",")
						.append("\"exitReason\":").append(jsonString(rs.getString("exitReason"))).append(",")
						.append("\"tradeNotes\":").append(jsonString(rs.getString("tradeNotes")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	public static String getPortfolioBacktestTradesJson(int backtestId) {
		initializeStore();
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT * FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? ORDER BY portfolioTradeID ASC";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, backtestId);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					if (json.length() > 1) {
						json.append(",");
					}
					json.append("{")
						.append("\"id\":").append(rs.getInt("portfolioTradeID")).append(",")
						.append("\"symbol\":").append(jsonString(rs.getString("symbol"))).append(",")
						.append("\"strategyCode\":").append(jsonString(rs.getString("strategyCode"))).append(",")
						.append("\"strategyName\":").append(jsonString(rs.getString("strategyName"))).append(",")
						.append("\"side\":").append(jsonString(rs.getString("side"))).append(",")
						.append("\"contracts\":").append(rs.getInt("contracts")).append(",")
						.append("\"entry\":").append(rs.getDouble("entryPrice")).append(",")
						.append("\"exit\":").append(rs.getDouble("exitPrice")).append(",")
						.append("\"stop\":").append(rs.getDouble("stopPrice")).append(",")
						.append("\"target\":").append(rs.getDouble("targetPrice")).append(",")
						.append("\"openedAt\":").append(jsonString(rs.getString("openedAt"))).append(",")
						.append("\"closedAt\":").append(jsonString(rs.getString("closedAt"))).append(",")
						.append("\"pnl\":").append(rs.getDouble("pnl")).append(",")
						.append("\"mfe\":").append(rs.getDouble("mfe")).append(",")
						.append("\"mae\":").append(rs.getDouble("mae")).append(",")
						.append("\"exitReason\":").append(jsonString(rs.getString("exitReason"))).append(",")
						.append("\"tradeNotes\":").append(jsonString(rs.getString("tradeNotes")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	public static String getBacktestSegmentsJson(int backtestId) {
		initializeStore();
		return "{"
			+ "\"monthly\":" + getSegmentRowsJson(backtestId, "substr(openedAt, 1, 7)") + ","
			+ "\"quarterly\":" + getQuarterRowsJson(backtestId)
			+ "}";
	}

	public static String getPortfolioBacktestSegmentsJson(int backtestId) {
		initializeStore();
		return "{"
			+ "\"monthly\":" + getPortfolioSegmentRowsJson(backtestId, "substr(openedAt, 1, 7)") + ","
			+ "\"quarterly\":" + getPortfolioQuarterRowsJson(backtestId)
			+ "}";
	}

	public static String getPortfolioBacktestSymbolsJson(int backtestId) {
		initializeStore();
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT symbol, COUNT(*) AS trades, SUM(pnl) AS pnl, AVG(pnl) AS avgPnl, "
			+ "SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins, AVG(mfe) AS avgMfe, AVG(mae) AS avgMae "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY symbol ORDER BY symbol";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, backtestId);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					if (json.length() > 1) {
						json.append(",");
					}
					int trades = rs.getInt("trades");
					double wins = rs.getDouble("wins");
					json.append("{")
						.append("\"symbol\":").append(jsonString(rs.getString("symbol"))).append(",")
						.append("\"trades\":").append(trades).append(",")
						.append("\"pnl\":").append(round(rs.getDouble("pnl"))).append(",")
						.append("\"avgPnl\":").append(round(rs.getDouble("avgPnl"))).append(",")
						.append("\"winRate\":").append(trades == 0 ? 0.0 : round((wins * 100.0) / trades)).append(",")
						.append("\"avgMfe\":").append(round(rs.getDouble("avgMfe"))).append(",")
						.append("\"avgMae\":").append(round(rs.getDouble("avgMae")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	public static String getStrategyLabJson(
		String symbols,
		String startDate,
		String endDate,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double commissionPerContract,
		double slippageTicks,
		boolean useSavedRisk
	) {
		initializeStore();
		List<String> symbolList = parseSymbols(symbols);
		StringBuilder json = new StringBuilder("{\"results\":[");
		boolean appended = false;
		for (int symbolIndex = 0; symbolIndex < symbolList.size(); symbolIndex++) {
			String symbol = normalizeSymbol(symbolList.get(symbolIndex));
			if (!hasNativeFuturesData(symbol)) {
				continue;
			}
			FuturesStrategySettings baseSettings = loadFuturesStrategySettings(symbol);
			FuturesRiskSettings riskSettings = useSavedRisk ? loadFuturesRiskSettings(symbol) : null;
			List<LabVariant> variants = labVariants(baseSettings);
			for (int variantIndex = 0; variantIndex < variants.size(); variantIndex++) {
				LabVariant variant = variants.get(variantIndex);
				BacktestConfig config = buildBacktestConfig(
					symbol,
					startDate,
					endDate,
					riskSettings == null ? accountSize : riskSettings.accountSize,
					riskSettings == null ? maxTrailingDrawdown : riskSettings.maxTrailingDrawdown,
					riskSettings == null ? dailyLossLimit : riskSettings.dailyLossLimit,
					riskSettings == null ? maxRiskPerTrade : riskSettings.maxRiskPerTrade,
					riskSettings == null ? maxContracts : riskSettings.maxContracts,
					commissionPerContract,
					slippageTicks,
					0.0
				);
				config.strategySettings = variant.settings;
				BacktestResult result = runBacktest(config);
				if (result == null) {
					continue;
				}
				if (appended) {
					json.append(",");
				}
				appendLabResult(json, variant.name, result);
				appended = true;
			}
		}
		json.append("]}");
		return json.toString();
	}

	public static String getStrategyDiagnosticsJson(
		String symbol,
		String startDate,
		String endDate,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double commissionPerContract,
		double slippageTicks,
		boolean useSavedRisk
	) {
		initializeStore();
		String normalizedSymbol = normalizeSymbol(symbol);
		FuturesRiskSettings riskSettings = useSavedRisk ? loadFuturesRiskSettings(normalizedSymbol) : null;
		BacktestConfig config = buildBacktestConfig(
			normalizedSymbol,
			startDate,
			endDate,
			riskSettings == null ? accountSize : riskSettings.accountSize,
			riskSettings == null ? maxTrailingDrawdown : riskSettings.maxTrailingDrawdown,
			riskSettings == null ? dailyLossLimit : riskSettings.dailyLossLimit,
			riskSettings == null ? maxRiskPerTrade : riskSettings.maxRiskPerTrade,
			riskSettings == null ? maxContracts : riskSettings.maxContracts,
			commissionPerContract,
			slippageTicks,
			0.0
		);
		InstrumentSpec spec = instrumentFor(config.symbol);
		File file = new File(DATA_DIR + "/" + TIMEFRAME_FOLDER + "/" + spec.symbol + ".csv");
		DataBundle rawBundle = loadCsv(file, config.startDate, config.endDate, 1.0, "native futures csv raw", false);
		DataBundle rthBundle = loadCsv(file, config.startDate, config.endDate, 1.0, "native futures csv rth", true);
		DataBundle fifteenMinuteBundle = loadNativeFuturesBars(spec.symbol, config.startDate, config.endDate, "15min");
		DataBundle oneHourBundle = loadNativeFuturesBars(spec.symbol, config.startDate, config.endDate, "1hour");
		Map<LocalDate, List<Bar>> byDay = groupByDay(rthBundle.bars);
		Map<LocalDate, List<Bar>> fifteenMinuteByDay = groupByDay(fifteenMinuteBundle.bars);
		Map<LocalDate, List<Bar>> oneHourByDay = groupByDay(oneHourBundle.bars);
		List<LocalDate> days = new ArrayList<LocalDate>(byDay.keySet());
		Collections.sort(days);

		Map<String, SignalStats> stats = new HashMap<String, SignalStats>();
		for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
			LocalDate day = days.get(dayIndex);
			List<Bar> bars = byDay.get(day);
			if (bars == null || bars.size() < 40) {
				continue;
			}
			List<Signal> signals = buildSignals(
				spec,
				bars,
				previousDayBars(byDay, days, dayIndex),
				fifteenMinuteByDay.get(day),
				oneHourByDay.get(day),
				config
			);
			for (int index = 0; index < signals.size(); index++) {
				recordSignalDiagnostic(stats, spec, bars, signals.get(index), config);
			}
		}

		BacktestResult result = runBacktest(config);
		if (result != null) {
			for (int index = 0; index < result.tradeRecords.size(); index++) {
				FuturesTrade trade = result.tradeRecords.get(index);
				SignalStats stat = statsFor(stats, trade.strategyCode, trade.strategyName);
				stat.actualTrades++;
			}
		}

		BarStats rawStats = statsForBars(rawBundle.bars);
		BarStats rthStats = statsForBars(rthBundle.bars);
		StringBuilder json = new StringBuilder("{");
		json.append("\"symbol\":").append(jsonString(spec.symbol)).append(",")
			.append("\"startDate\":").append(jsonString(config.startDate.toString())).append(",")
			.append("\"endDate\":").append(jsonString(config.endDate.toString())).append(",")
			.append("\"data\":{")
			.append("\"rawFileRows\":").append(countCsvDataRows(file)).append(",")
			.append("\"selectedRawRows\":").append(rawStats.rows).append(",")
			.append("\"selectedRawDays\":").append(rawStats.days).append(",")
			.append("\"selectedRthRows\":").append(rthStats.rows).append(",")
			.append("\"selectedRthDays\":").append(rthStats.days).append(",")
			.append("\"firstRawBar\":").append(jsonString(rawStats.first)).append(",")
			.append("\"lastRawBar\":").append(jsonString(rawStats.last)).append(",")
			.append("\"firstRthBar\":").append(jsonString(rthStats.first)).append(",")
			.append("\"lastRthBar\":").append(jsonString(rthStats.last)).append(",")
			.append("\"rthRowsPerDay\":").append(rthStats.days == 0 ? 0.0 : round((double) rthStats.rows / rthStats.days)).append(",")
			.append("\"expectedFullRthRowsPerDay\":390")
			.append("},");
		json.append("\"comparison\":{")
			.append("\"futuresRawRowsBySymbol\":").append(rowsBySymbolJson(DATA_DIR + "/" + TIMEFRAME_FOLDER, supportedInstrumentSymbols())).append(",")
			.append("\"stockRowsBySymbol\":").append(rowsBySymbolJson("market_data/1min", stockComparisonSymbols()))
			.append("},");
		json.append("\"signals\":[");
		List<SignalStats> orderedStats = new ArrayList<SignalStats>(stats.values());
		Collections.sort(orderedStats, new Comparator<SignalStats>() {
			@Override
			public int compare(SignalStats first, SignalStats second) {
				return first.code.compareTo(second.code);
			}
		});
		for (int index = 0; index < orderedStats.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			appendSignalStats(json, orderedStats.get(index));
		}
		json.append("],");
		if (result == null) {
			json.append("\"backtest\":null,");
		} else {
			json.append("\"backtest\":{")
				.append("\"profit\":").append(result.totalProfit).append(",")
				.append("\"trades\":").append(result.trades).append(",")
				.append("\"winRate\":").append(result.winRate).append(",")
				.append("\"profitFactor\":").append(result.profitFactor).append(",")
				.append("\"maxDrawdownPct\":").append(result.maxDrawdownPct).append(",")
				.append("\"ruleViolation\":").append(result.ruleViolation).append(",")
				.append("\"ruleMessage\":").append(jsonString(result.ruleMessage)).append(",")
				.append("\"dataSource\":").append(jsonString(result.dataSource))
				.append("},");
		}
		json.append("\"bottlenecks\":").append(diagnosticBottlenecksJson(rawStats, rthStats, orderedStats, result, config.strategySettings));
		json.append("}");
		return json.toString();
	}

	private static String getSegmentRowsJson(int backtestId, String segmentExpression) {
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT " + segmentExpression + " AS segment, COUNT(*) AS trades, SUM(pnl) AS pnl, "
			+ "AVG(pnl) AS avgPnl, SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins, "
			+ "AVG(mfe) AS avgMfe, AVG(mae) AS avgMae "
			+ "FROM FuturesTrades WHERE futuresBacktestID = ? GROUP BY segment ORDER BY segment";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, backtestId);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					if (json.length() > 1) {
						json.append(",");
					}
					int trades = rs.getInt("trades");
					double wins = rs.getDouble("wins");
					json.append("{")
						.append("\"segment\":").append(jsonString(rs.getString("segment"))).append(",")
						.append("\"trades\":").append(trades).append(",")
						.append("\"pnl\":").append(round(rs.getDouble("pnl"))).append(",")
						.append("\"avgPnl\":").append(round(rs.getDouble("avgPnl"))).append(",")
						.append("\"winRate\":").append(trades == 0 ? 0.0 : round((wins * 100.0) / trades)).append(",")
						.append("\"avgMfe\":").append(round(rs.getDouble("avgMfe"))).append(",")
						.append("\"avgMae\":").append(round(rs.getDouble("avgMae")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	private static String getQuarterRowsJson(int backtestId) {
		String quarterExpression = "substr(openedAt, 1, 4) || '-Q' || ((CAST(substr(openedAt, 6, 2) AS INTEGER) + 2) / 3)";
		return getSegmentRowsJson(backtestId, quarterExpression);
	}

	private static String getPortfolioSegmentRowsJson(int backtestId, String segmentExpression) {
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT " + segmentExpression + " AS segment, COUNT(*) AS trades, SUM(pnl) AS pnl, "
			+ "AVG(pnl) AS avgPnl, SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) AS wins, "
			+ "AVG(mfe) AS avgMfe, AVG(mae) AS avgMae "
			+ "FROM FuturesPortfolioTrades WHERE portfolioBacktestID = ? GROUP BY segment ORDER BY segment";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, backtestId);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					if (json.length() > 1) {
						json.append(",");
					}
					int trades = rs.getInt("trades");
					double wins = rs.getDouble("wins");
					json.append("{")
						.append("\"segment\":").append(jsonString(rs.getString("segment"))).append(",")
						.append("\"trades\":").append(trades).append(",")
						.append("\"pnl\":").append(round(rs.getDouble("pnl"))).append(",")
						.append("\"avgPnl\":").append(round(rs.getDouble("avgPnl"))).append(",")
						.append("\"winRate\":").append(trades == 0 ? 0.0 : round((wins * 100.0) / trades)).append(",")
						.append("\"avgMfe\":").append(round(rs.getDouble("avgMfe"))).append(",")
						.append("\"avgMae\":").append(round(rs.getDouble("avgMae")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	private static String getPortfolioQuarterRowsJson(int backtestId) {
		String quarterExpression = "substr(openedAt, 1, 4) || '-Q' || ((CAST(substr(openedAt, 6, 2) AS INTEGER) + 2) / 3)";
		return getPortfolioSegmentRowsJson(backtestId, quarterExpression);
	}

	private static void recordSignalDiagnostic(Map<String, SignalStats> stats, InstrumentSpec spec, List<Bar> bars, Signal signal, BacktestConfig config) {
		if (signal == null) {
			return;
		}
		SignalStats stat = statsFor(stats, signal.strategyCode, signal.strategyName);
		stat.generated++;
		int executionIndex = signal.entryIndex + 1;
		if (executionIndex >= bars.size()) {
			stat.lateRejected++;
			return;
		}

		Bar entryBar = bars.get(executionIndex);
		if (!entryBar.marketTime.isBefore(FORCED_EXIT_TIME)) {
			stat.lateRejected++;
			return;
		}

		double entryPrice = applySlippage(spec, entryBar.open, signal.side, config.slippageTicks, true);
		double stopPrice = roundToTick(spec, signal.stopPrice);
		if ("LONG".equals(signal.side) && entryPrice <= stopPrice) {
			stat.riskRejected++;
			return;
		}
		if ("SHORT".equals(signal.side) && entryPrice >= stopPrice) {
			stat.riskRejected++;
			return;
		}

		double rawRiskTicks = Math.abs(entryPrice - stopPrice) / spec.tickSize;
		FuturesStrategySettings settings = config.strategySettings == null ? defaultFuturesStrategySettings() : config.strategySettings;
		if (rawRiskTicks < 1.0 || rawRiskTicks > settings.maxInitialRiskTicks) {
			stat.riskRejected++;
			return;
		}

		double stopFillForRisk = applySlippage(spec, stopPrice, signal.side, config.slippageTicks, false);
		double riskPerContract = (Math.abs(entryPrice - stopFillForRisk) / spec.tickSize * spec.tickValue) + (config.commissionPerContract * 2.0);
		double availableRiskBudget = Math.min(config.maxRiskPerTrade, Math.min(Math.abs(config.dailyLossLimit), config.maxTrailingDrawdown));
		int contracts = Math.min(config.maxContracts, (int) Math.floor(availableRiskBudget / Math.max(1.0, riskPerContract)));
		if (contracts < 1) {
			stat.contractRejected++;
			return;
		}
		stat.executable++;
	}

	private static SignalStats statsFor(Map<String, SignalStats> stats, String strategyCode, String strategyName) {
		String code = strategyCode == null || strategyCode.trim().isEmpty() ? "UNKNOWN" : strategyCode.trim();
		SignalStats stat = stats.get(code);
		if (stat == null) {
			stat = new SignalStats();
			stat.code = code;
			stat.name = strategyName == null || strategyName.trim().isEmpty() ? code : strategyName.trim();
			stats.put(code, stat);
		}
		return stat;
	}

	private static BarStats statsForBars(List<Bar> bars) {
		BarStats stats = new BarStats();
		if (bars == null || bars.isEmpty()) {
			return stats;
		}
		Map<LocalDate, Boolean> dates = new HashMap<LocalDate, Boolean>();
		stats.rows = bars.size();
		stats.first = bars.get(0).displayTime;
		stats.last = bars.get(bars.size() - 1).displayTime;
		for (int index = 0; index < bars.size(); index++) {
			dates.put(bars.get(index).marketDate, true);
		}
		stats.days = dates.size();
		return stats;
	}

	private static void appendSignalStats(StringBuilder json, SignalStats stat) {
		json.append("{")
			.append("\"code\":").append(jsonString(stat.code)).append(",")
			.append("\"name\":").append(jsonString(stat.name)).append(",")
			.append("\"generated\":").append(stat.generated).append(",")
			.append("\"executable\":").append(stat.executable).append(",")
			.append("\"riskRejected\":").append(stat.riskRejected).append(",")
			.append("\"lateRejected\":").append(stat.lateRejected).append(",")
			.append("\"contractRejected\":").append(stat.contractRejected).append(",")
			.append("\"actualTrades\":").append(stat.actualTrades)
			.append("}");
	}

	private static String diagnosticBottlenecksJson(BarStats rawStats, BarStats rthStats, List<SignalStats> signalStats, BacktestResult result, FuturesStrategySettings settings) {
		List<String> notes = new ArrayList<String>();
		if (rawStats.rows > 0 && rthStats.rows > 0) {
			notes.add("Databento is not row-limiting the current file: raw Globex/extended-hours rows are present, while the backtest intentionally executes only regular trading hours.");
		}
		if (rthStats.days > 0) {
			notes.add("The selected range has about " + round((double) rthStats.rows / rthStats.days) + " executable RTH 1-minute bars per session, close to the 390-minute cash session after holidays and short sessions.");
		}
		if (!hasEnabledStrategyModule(settings)) {
			notes.add("No strategy modules are enabled for this contract profile, so a zero-trade backtest is a configuration state rather than a successful trading edge.");
		}

		int executable = 0;
		for (int index = 0; index < signalStats.size(); index++) {
			SignalStats stat = signalStats.get(index);
			executable += stat.executable;
			if ("ORB".equals(stat.code) && stat.riskRejected > 0) {
				notes.add("Opening Range Breakout is the main frequency bottleneck: many filtered ORB signals still exceed maxInitialRiskTicks once next-bar open and slippage are applied.");
			}
		}
		if (result != null && executable > result.trades) {
			notes.add("Executable signal count is higher than final trades because no-overlap, max-trades-per-day, daily-loss, and trailing-drawdown rules decide what can actually be taken.");
		}
		if (result != null && result.trades < 100) {
			notes.add("A 300-400 trade/year target is not supported by the current 1-minute RTH strategy set without loosening integrity; dedicated scalping logic should be validated separately before promotion.");
		}
		return jsonStringArray(notes);
	}

	private static boolean hasEnabledStrategyModule(FuturesStrategySettings settings) {
		FuturesStrategySettings safe = settings == null ? defaultFuturesStrategySettings() : settings;
		return safe.orb.enabled
			|| safe.openingMomentum.enabled
			|| safe.sweep.enabled
			|| safe.vwapPullback.enabled
			|| safe.vwapMeanReversion.enabled
			|| safe.fvg.enabled
			|| safe.closeMomentum.enabled
			|| safe.afternoonContinuation.enabled
			|| safe.marketIntradayMomentum.enabled
			|| safe.keltnerScalp.enabled
			|| safe.keltnerReversion.enabled
			|| safe.microScalp.enabled;
	}

	private static String jsonStringArray(List<String> values) {
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < values.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			json.append(jsonString(values.get(index)));
		}
		json.append("]");
		return json.toString();
	}

	private static String rowsBySymbolJson(String folderPath, List<String> symbols) {
		StringBuilder json = new StringBuilder("{");
		for (int index = 0; index < symbols.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			String symbol = symbols.get(index);
			json.append(jsonString(symbol)).append(":").append(countCsvDataRows(new File(folderPath, symbol + ".csv")));
		}
		json.append("}");
		return json.toString();
	}

	private static List<String> supportedInstrumentSymbols() {
		List<InstrumentSpec> specs = supportedInstruments();
		List<String> symbols = new ArrayList<String>();
		for (int index = 0; index < specs.size(); index++) {
			symbols.add(specs.get(index).symbol);
		}
		return symbols;
	}

	private static List<String> stockComparisonSymbols() {
		List<String> symbols = new ArrayList<String>();
		symbols.add("SPY");
		symbols.add("QQQ");
		symbols.add("NVDA");
		symbols.add("TSLA");
		symbols.add("AAPL");
		return symbols;
	}

	private static int countCsvDataRows(File file) {
		if (file == null || !file.exists()) {
			return 0;
		}
		int rows = 0;
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line = reader.readLine();
			while ((line = reader.readLine()) != null) {
				if (!line.trim().isEmpty()) {
					rows++;
				}
			}
		} catch (Exception e) {
			return 0;
		}
		return rows;
	}

	private static BacktestConfig buildBacktestConfig(
		String symbol,
		String startDate,
		String endDate,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double commissionPerContract,
		double slippageTicks,
		double profitTarget
	) {
		BacktestConfig config = new BacktestConfig();
		config.symbol = normalizeSymbol(symbol);
		FuturesRiskSettings riskSettings = loadFuturesRiskSettings(config.symbol);
		config.startDate = parseDate(startDate, LocalDate.now().minusYears(1));
		config.endDate = parseDate(endDate, LocalDate.now());
		config.accountSize = positiveOrDefault(accountSize, riskSettings.accountSize);
		config.maxTrailingDrawdown = positiveOrDefault(maxTrailingDrawdown, riskSettings.maxTrailingDrawdown);
		config.dailyLossLimit = positiveOrDefault(dailyLossLimit, riskSettings.dailyLossLimit);
		config.maxRiskPerTrade = positiveOrDefault(maxRiskPerTrade, riskSettings.maxRiskPerTrade);
		config.maxContracts = boundedInt(maxContracts, riskSettings.maxContracts, 1, 50);
		config.commissionPerContract = commissionPerContract >= 0.0 ? commissionPerContract : riskSettings.commissionPerContract;
		config.slippageTicks = slippageTicks >= 0.0 ? slippageTicks : riskSettings.slippageTicks;
		config.profitTarget = profitTarget >= 0.0 ? profitTarget : riskSettings.profitTarget;
		config.strategySettings = loadFuturesStrategySettings(config.symbol);
		return config;
	}

	private static List<String> parseSymbols(String symbols) {
		List<String> values = new ArrayList<String>();
		if (symbols != null) {
			String[] parts = symbols.split(",");
			for (int index = 0; index < parts.length; index++) {
				String normalized = normalizeSymbol(parts[index]);
				if (!values.contains(normalized)) {
					values.add(normalized);
				}
			}
		}
		if (values.isEmpty()) {
			values.addAll(supportedInstrumentSymbols());
		}
		return values;
	}

	private static boolean hasNativeFuturesData(String symbol) {
		InstrumentSpec spec = instrumentFor(symbol);
		File file = new File(DATA_DIR + "/" + TIMEFRAME_FOLDER + "/" + spec.symbol + ".csv");
		return file.exists() && file.length() > 0L;
	}

	private static class LabVariant {
		private String name;
		private FuturesStrategySettings settings;

		private LabVariant(String name, FuturesStrategySettings settings) {
			this.name = name;
			this.settings = settings;
		}
	}

	private static List<LabVariant> labVariants(FuturesStrategySettings baseSettings) {
		List<LabVariant> variants = new ArrayList<LabVariant>();
		FuturesStrategySettings baseline = cloneSettings(baseSettings);
		variants.add(new LabVariant("Baseline", baseline));

		FuturesStrategySettings adaptiveRunner = cloneSettings(baseSettings);
		adaptiveRunner.enableAdaptiveExits = true;
		adaptiveRunner.adaptiveMinVolumeRatio = 1.1;
		adaptiveRunner.adaptiveMinBodyPct = 32.0;
		adaptiveRunner.adaptiveTrendTargetBoost = 0.25;
		adaptiveRunner.adaptiveVolumeTargetBoost = 0.25;
		adaptiveRunner.adaptiveBodyTargetBoost = 0.15;
		adaptiveRunner.adaptiveMaxRewardRisk = 2.2;
		variants.add(new LabVariant("Adaptive Runner", adaptiveRunner));

		FuturesStrategySettings adaptiveRunnerWide = cloneSettings(adaptiveRunner);
		adaptiveRunnerWide.adaptiveTrendTargetBoost = 0.45;
		adaptiveRunnerWide.adaptiveVolumeTargetBoost = 0.35;
		adaptiveRunnerWide.adaptiveBodyTargetBoost = 0.25;
		adaptiveRunnerWide.adaptiveMaxRewardRisk = 2.8;
		variants.add(new LabVariant("Adaptive Runner Wide", adaptiveRunnerWide));

		FuturesStrategySettings lossCutOnly = cloneSettings(baseSettings);
		lossCutOnly.enableEarlyLossCut = true;
		lossCutOnly.earlyLossCutBars = 18;
		lossCutOnly.earlyLossCutR = 0.6;
		lossCutOnly.earlyLossCutMinFavorableR = 0.2;
		variants.add(new LabVariant("Adaptive Loss Cut", lossCutOnly));

		FuturesStrategySettings runnerLossCut = cloneSettings(adaptiveRunner);
		runnerLossCut.enableEarlyLossCut = true;
		runnerLossCut.earlyLossCutBars = 18;
		runnerLossCut.earlyLossCutR = 0.6;
		runnerLossCut.earlyLossCutMinFavorableR = 0.2;
		variants.add(new LabVariant("Adaptive Runner + Loss Cut", runnerLossCut));

		FuturesStrategySettings maeGuard = cloneSettings(baseSettings);
		maeGuard.openMaeRiskMultiplier = 2.0;
		variants.add(new LabVariant("MAE Funded Guard", maeGuard));

		FuturesStrategySettings runnerMaeGuard = cloneSettings(adaptiveRunner);
		runnerMaeGuard.openMaeRiskMultiplier = 2.0;
		variants.add(new LabVariant("Adaptive Runner + MAE Guard", runnerMaeGuard));

		FuturesStrategySettings lossCutMaeGuard = cloneSettings(lossCutOnly);
		lossCutMaeGuard.openMaeRiskMultiplier = 2.0;
		variants.add(new LabVariant("Loss Cut + MAE Guard", lossCutMaeGuard));

		FuturesStrategySettings highExpectancyFrequency = cloneSettings(adaptiveRunner);
		highExpectancyFrequency.openingMomentum.enabled = true;
		highExpectancyFrequency.closeMomentum.enabled = true;
		highExpectancyFrequency.marketIntradayMomentum.enabled = true;
		highExpectancyFrequency.openingMomentum.maxTradesPerDay = Math.max(2, highExpectancyFrequency.openingMomentum.maxTradesPerDay);
		highExpectancyFrequency.closeMomentum.maxTradesPerDay = Math.max(1, highExpectancyFrequency.closeMomentum.maxTradesPerDay);
		highExpectancyFrequency.marketIntradayMomentum.maxTradesPerDay = Math.max(1, highExpectancyFrequency.marketIntradayMomentum.maxTradesPerDay);
		highExpectancyFrequency.openingMomentumRewardRisk = Math.max(highExpectancyFrequency.openingMomentumRewardRisk, 1.0);
		highExpectancyFrequency.closeMomentumRewardRisk = Math.max(highExpectancyFrequency.closeMomentumRewardRisk, 1.0);
		highExpectancyFrequency.marketIntradayMomentumRewardRisk = Math.max(highExpectancyFrequency.marketIntradayMomentumRewardRisk, 1.0);
		highExpectancyFrequency.enableEarlyLossCut = true;
		highExpectancyFrequency.earlyLossCutBars = 16;
		highExpectancyFrequency.earlyLossCutR = 0.55;
		variants.add(new LabVariant("Adaptive Frequency Stack", highExpectancyFrequency));

		FuturesStrategySettings orbRetest = cloneSettings(baseSettings);
		orbRetest.orb.enabled = true;
		orbRetest.enableOrbRetest = true;
		orbRetest.orb.maxTradesPerDay = Math.max(2, orbRetest.orb.maxTradesPerDay);
		variants.add(new LabVariant("ORB Retest", orbRetest));

		FuturesStrategySettings compressedOrbBreakout = cloneSettings(baseSettings);
		compressedOrbBreakout.orb.enabled = true;
		compressedOrbBreakout.enableCompressedOrbBreakout = true;
		compressedOrbBreakout.orb.maxTradesPerDay = Math.max(2, compressedOrbBreakout.orb.maxTradesPerDay);
		compressedOrbBreakout.orbCompressedMaxRiskTicks = Math.min(compressedOrbBreakout.orbCompressedMaxRiskTicks, 48.0);
		compressedOrbBreakout.minRewardRisk = Math.max(compressedOrbBreakout.minRewardRisk, 1.1);
		variants.add(new LabVariant("ORB Compressed Breakout", compressedOrbBreakout));

		FuturesStrategySettings openingMomentum = cloneSettings(baseSettings);
		openingMomentum.openingMomentum.enabled = true;
		openingMomentum.openingMomentum.maxTradesPerDay = Math.max(2, openingMomentum.openingMomentum.maxTradesPerDay);
		openingMomentum.openingMomentumRewardRisk = 0.8;
		openingMomentum.openingMomentumVolumeRatio = 0.5;
		variants.add(new LabVariant("Opening Momentum", openingMomentum));

		FuturesStrategySettings conservativeOpeningMomentum = cloneSettings(baseSettings);
		conservativeOpeningMomentum.openingMomentum.enabled = true;
		conservativeOpeningMomentum.openingMomentum.maxTradesPerDay = Math.max(2, conservativeOpeningMomentum.openingMomentum.maxTradesPerDay);
		conservativeOpeningMomentum.openingMomentumRangeMinutes = 5;
		conservativeOpeningMomentum.openingMomentumMaxHoldBars = 45;
		conservativeOpeningMomentum.openingMomentumRewardRisk = 1.0;
		conservativeOpeningMomentum.openingMomentumVolumeRatio = 0.5;
		conservativeOpeningMomentum.maxInitialRiskTicks = Math.min(conservativeOpeningMomentum.maxInitialRiskTicks, 40.0);
		variants.add(new LabVariant("Opening Momentum Conservative", conservativeOpeningMomentum));

		FuturesStrategySettings compressedOrbRetest = cloneSettings(baseSettings);
		compressedOrbRetest.orb.enabled = true;
		compressedOrbRetest.enableOrbRetest = true;
		compressedOrbRetest.orb.maxTradesPerDay = Math.max(2, compressedOrbRetest.orb.maxTradesPerDay);
		compressedOrbRetest.maxInitialRiskTicks = Math.min(compressedOrbRetest.maxInitialRiskTicks, 40.0);
		compressedOrbRetest.minRewardRisk = Math.max(compressedOrbRetest.minRewardRisk, 1.15);
		variants.add(new LabVariant("ORB Retest Compressed", compressedOrbRetest));

		FuturesStrategySettings orbRetestQuality = cloneSettings(baseSettings);
		orbRetestQuality.orb.enabled = true;
		orbRetestQuality.enableOrbRetest = true;
		orbRetestQuality.skipMidmorningOrbRetest = true;
		orbRetestQuality.orb.maxTradesPerDay = Math.max(2, orbRetestQuality.orb.maxTradesPerDay);
		variants.add(new LabVariant("ORB Retest Quality", orbRetestQuality));

		FuturesStrategySettings orbRetestQualityClose = cloneSettings(orbRetestQuality);
		orbRetestQualityClose.closeMomentum.enabled = true;
		orbRetestQualityClose.closeMomentum.maxTradesPerDay = 1;
		orbRetestQualityClose.closeMomentumMinMoveTicks = Math.min(orbRetestQualityClose.closeMomentumMinMoveTicks, 20.0);
		orbRetestQualityClose.closeMomentumVolumeRatio = Math.min(orbRetestQualityClose.closeMomentumVolumeRatio, 0.7);
		orbRetestQualityClose.closeMomentumRewardRisk = 0.85;
		variants.add(new LabVariant("ORB Retest Quality + Close", orbRetestQualityClose));

		FuturesStrategySettings meanReversion = cloneSettings(baseSettings);
		meanReversion.vwapMeanReversion.enabled = true;
		meanReversion.vwapMeanReversion.maxTradesPerDay = 1;
		meanReversion.vwapPullback.enabled = false;
		variants.add(new LabVariant("MRVWAP Expansion", meanReversion));

		FuturesStrategySettings vwap = cloneSettings(baseSettings);
		vwap.vwapPullback.enabled = true;
		vwap.vwapPullback.maxTradesPerDay = 1;
		vwap.vwapMinVolumeRatio = Math.max(1.25, vwap.vwapMinVolumeRatio);
		vwap.vwapMinTrendSlopeTicks = Math.max(6.0, vwap.vwapMinTrendSlopeTicks);
		variants.add(new LabVariant("VWAP Strict", vwap));

		FuturesStrategySettings vwapCompressed = cloneSettings(baseSettings);
		vwapCompressed.vwapPullback.enabled = true;
		vwapCompressed.vwapPullback.maxTradesPerDay = Math.max(3, vwapCompressed.vwapPullback.maxTradesPerDay);
		vwapCompressed.vwapMinVolumeRatio = Math.max(1.1, vwapCompressed.vwapMinVolumeRatio);
		vwapCompressed.vwapMinTrendSlopeTicks = Math.max(1.5, vwapCompressed.vwapMinTrendSlopeTicks);
		vwapCompressed.vwapMaxDistanceTicks = Math.max(120.0, vwapCompressed.vwapMaxDistanceTicks);
		vwapCompressed.minRewardRisk = Math.max(vwapCompressed.minRewardRisk, 1.1);
		variants.add(new LabVariant("VWAP Compressed Continuation", vwapCompressed));

		FuturesStrategySettings afternoon = cloneSettings(baseSettings);
		afternoon.afternoonContinuation.enabled = true;
		afternoon.afternoonContinuation.maxTradesPerDay = Math.max(2, afternoon.afternoonContinuation.maxTradesPerDay);
		afternoon.afternoonMinVolumeRatio = Math.min(afternoon.afternoonMinVolumeRatio, 0.8);
		afternoon.afternoonMaxRiskTicks = Math.min(afternoon.afternoonMaxRiskTicks, 48.0);
		afternoon.afternoonRewardRisk = 0.9;
		variants.add(new LabVariant("Afternoon Continuation", afternoon));

		FuturesStrategySettings afternoonStrict = cloneSettings(baseSettings);
		afternoonStrict.afternoonContinuation.enabled = true;
		afternoonStrict.afternoonContinuation.maxTradesPerDay = 1;
		afternoonStrict.afternoonMinVolumeRatio = Math.max(afternoonStrict.afternoonMinVolumeRatio, 1.1);
		afternoonStrict.afternoonMaxRiskTicks = Math.min(afternoonStrict.afternoonMaxRiskTicks, 36.0);
		afternoonStrict.afternoonRewardRisk = 1.1;
		variants.add(new LabVariant("Afternoon Continuation Strict", afternoonStrict));

		FuturesStrategySettings marketIntradayMomentum = cloneSettings(baseSettings);
		marketIntradayMomentum.marketIntradayMomentum.enabled = true;
		marketIntradayMomentum.marketIntradayMomentum.maxTradesPerDay = 1;
		marketIntradayMomentum.marketIntradayMomentumMinOpenMoveTicks = Math.min(marketIntradayMomentum.marketIntradayMomentumMinOpenMoveTicks, 12.0);
		marketIntradayMomentum.marketIntradayMomentumMinLateMoveTicks = Math.min(marketIntradayMomentum.marketIntradayMomentumMinLateMoveTicks, 8.0);
		marketIntradayMomentum.marketIntradayMomentumMinVolumeRatio = Math.min(marketIntradayMomentum.marketIntradayMomentumMinVolumeRatio, 0.6);
		marketIntradayMomentum.marketIntradayMomentumMaxRiskTicks = Math.min(marketIntradayMomentum.marketIntradayMomentumMaxRiskTicks, 48.0);
		marketIntradayMomentum.marketIntradayMomentumRewardRisk = 0.8;
		variants.add(new LabVariant("Market Intraday Momentum", marketIntradayMomentum));

		FuturesStrategySettings marketIntradayMomentumStrict = cloneSettings(baseSettings);
		marketIntradayMomentumStrict.marketIntradayMomentum.enabled = true;
		marketIntradayMomentumStrict.marketIntradayMomentum.maxTradesPerDay = 1;
		marketIntradayMomentumStrict.marketIntradayMomentumMinOpenMoveTicks = Math.max(marketIntradayMomentumStrict.marketIntradayMomentumMinOpenMoveTicks, 20.0);
		marketIntradayMomentumStrict.marketIntradayMomentumMinLateMoveTicks = Math.max(marketIntradayMomentumStrict.marketIntradayMomentumMinLateMoveTicks, 12.0);
		marketIntradayMomentumStrict.marketIntradayMomentumMinVolumeRatio = Math.max(marketIntradayMomentumStrict.marketIntradayMomentumMinVolumeRatio, 0.9);
		marketIntradayMomentumStrict.marketIntradayMomentumMaxRiskTicks = Math.min(marketIntradayMomentumStrict.marketIntradayMomentumMaxRiskTicks, 36.0);
		marketIntradayMomentumStrict.marketIntradayMomentumRewardRisk = 1.0;
		variants.add(new LabVariant("Market Intraday Momentum Strict", marketIntradayMomentumStrict));

		FuturesStrategySettings impulsePullback = cloneSettings(baseSettings);
		impulsePullback.marketIntradayMomentum.enabled = true;
		impulsePullback.marketIntradayMomentum.maxTradesPerDay = Math.max(2, impulsePullback.marketIntradayMomentum.maxTradesPerDay);
		impulsePullback.marketIntradayMomentumMinOpenMoveTicks = Math.max(impulsePullback.marketIntradayMomentumMinOpenMoveTicks, 16.0);
		impulsePullback.marketIntradayMomentumMinLateMoveTicks = Math.max(impulsePullback.marketIntradayMomentumMinLateMoveTicks, 10.0);
		impulsePullback.marketIntradayMomentumMinVolumeRatio = Math.max(impulsePullback.marketIntradayMomentumMinVolumeRatio, 0.8);
		impulsePullback.marketIntradayMomentumMaxRiskTicks = Math.min(impulsePullback.marketIntradayMomentumMaxRiskTicks, 36.0);
		impulsePullback.marketIntradayMomentumRewardRisk = Math.max(impulsePullback.marketIntradayMomentumRewardRisk, 0.9);
		variants.add(new LabVariant("Opening Impulse Pullback", impulsePullback));

		FuturesStrategySettings microScalp = cloneSettings(baseSettings);
		microScalp.microScalp.enabled = true;
		microScalp.microScalp.maxTradesPerDay = Math.max(6, microScalp.microScalp.maxTradesPerDay);
		microScalp.microScalpMinVolumeRatio = Math.min(microScalp.microScalpMinVolumeRatio, 0.75);
		microScalp.microScalpMaxRiskTicks = Math.min(microScalp.microScalpMaxRiskTicks, 18.0);
		microScalp.microScalpRewardRisk = 0.85;
		microScalp.microScalpMaxHoldBars = 10;
		microScalp.microScalpBucketMinutes = 20;
		variants.add(new LabVariant("Micro Trend Scalp", microScalp));

		FuturesStrategySettings keltnerScalp = cloneSettings(baseSettings);
		keltnerScalp.keltnerScalp.enabled = true;
		keltnerScalp.keltnerScalp.maxTradesPerDay = Math.max(8, keltnerScalp.keltnerScalp.maxTradesPerDay);
		keltnerScalp.keltnerAtrMultiplier = Math.min(keltnerScalp.keltnerAtrMultiplier, 1.3);
		keltnerScalp.keltnerMinVolumeRatio = Math.min(keltnerScalp.keltnerMinVolumeRatio, 0.75);
		keltnerScalp.keltnerMaxRiskTicks = Math.min(keltnerScalp.keltnerMaxRiskTicks, 22.0);
		keltnerScalp.keltnerRewardRisk = 0.85;
		keltnerScalp.keltnerBucketMinutes = 12;
		variants.add(new LabVariant("Keltner ATR Breakout Scalp", keltnerScalp));

		FuturesStrategySettings keltnerReversion = cloneSettings(baseSettings);
		keltnerReversion.keltnerReversion.enabled = true;
		keltnerReversion.keltnerReversion.maxTradesPerDay = Math.max(6, keltnerReversion.keltnerReversion.maxTradesPerDay);
		keltnerReversion.keltnerScalp.enabled = false;
		keltnerReversion.keltnerAtrMultiplier = Math.max(keltnerReversion.keltnerAtrMultiplier, 1.45);
		keltnerReversion.keltnerMinVolumeRatio = Math.min(keltnerReversion.keltnerMinVolumeRatio, 0.65);
		keltnerReversion.keltnerMaxRiskTicks = Math.min(keltnerReversion.keltnerMaxRiskTicks, 22.0);
		keltnerReversion.keltnerRewardRisk = 0.85;
		keltnerReversion.keltnerBucketMinutes = 15;
		variants.add(new LabVariant("Keltner Band Reclaim Reversion", keltnerReversion));

		FuturesStrategySettings microScalpDense = cloneSettings(microScalp);
		microScalpDense.microScalp.maxTradesPerDay = 10;
		microScalpDense.microScalpMinVolumeRatio = 0.55;
		microScalpDense.microScalpMaxRiskTicks = Math.min(microScalpDense.microScalpMaxRiskTicks, 22.0);
		microScalpDense.microScalpRewardRisk = 0.75;
		microScalpDense.microScalpBucketMinutes = 12;
		variants.add(new LabVariant("Micro Trend Scalp Dense", microScalpDense));

		FuturesStrategySettings closeMomentum = cloneSettings(baseSettings);
		closeMomentum.closeMomentum.enabled = true;
		closeMomentum.closeMomentum.maxTradesPerDay = 1;
		closeMomentum.closeMomentumMinMoveTicks = Math.min(closeMomentum.closeMomentumMinMoveTicks, 20.0);
		closeMomentum.closeMomentumVolumeRatio = Math.min(closeMomentum.closeMomentumVolumeRatio, 0.7);
		closeMomentum.closeMomentumRewardRisk = 0.85;
		variants.add(new LabVariant("Close Momentum", closeMomentum));

		FuturesStrategySettings sweepFrequency = cloneSettings(baseSettings);
		sweepFrequency.sweep.maxTradesPerDay = Math.max(3, sweepFrequency.sweep.maxTradesPerDay);
		sweepFrequency.allowShorts = true;
		sweepFrequency.enableEarlySweep = false;
		sweepFrequency.enableLateSweep = true;
		sweepFrequency.earlySweepReclaimTicks = Math.min(sweepFrequency.earlySweepReclaimTicks, 4.0);
		sweepFrequency.lateSweepReclaimTicks = Math.min(sweepFrequency.lateSweepReclaimTicks, 4.0);
		sweepFrequency.lateSweepCloseLocation = Math.max(sweepFrequency.lateSweepCloseLocation, 0.6);
		sweepFrequency.minRewardRisk = Math.min(sweepFrequency.minRewardRisk, 1.0);
		variants.add(new LabVariant("SWEEP Frequency", sweepFrequency));
		return variants;
	}

	private static FuturesStrategySettings cloneSettings(FuturesStrategySettings source) {
		FuturesStrategySettings copy = defaultFuturesStrategySettings();
		FuturesStrategySettings safe = source == null ? defaultFuturesStrategySettings() : source;
		copy.orb = new StrategyToggle(safe.orb.enabled, safe.orb.maxTradesPerDay);
		copy.openingMomentum = new StrategyToggle(safe.openingMomentum.enabled, safe.openingMomentum.maxTradesPerDay);
		copy.sweep = new StrategyToggle(safe.sweep.enabled, safe.sweep.maxTradesPerDay);
		copy.vwapPullback = new StrategyToggle(safe.vwapPullback.enabled, safe.vwapPullback.maxTradesPerDay);
		copy.vwapMeanReversion = new StrategyToggle(safe.vwapMeanReversion.enabled, safe.vwapMeanReversion.maxTradesPerDay);
		copy.fvg = new StrategyToggle(safe.fvg.enabled, safe.fvg.maxTradesPerDay);
		copy.closeMomentum = new StrategyToggle(safe.closeMomentum.enabled, safe.closeMomentum.maxTradesPerDay);
		copy.afternoonContinuation = new StrategyToggle(safe.afternoonContinuation.enabled, safe.afternoonContinuation.maxTradesPerDay);
		copy.marketIntradayMomentum = new StrategyToggle(safe.marketIntradayMomentum.enabled, safe.marketIntradayMomentum.maxTradesPerDay);
		copy.keltnerScalp = new StrategyToggle(safe.keltnerScalp.enabled, safe.keltnerScalp.maxTradesPerDay);
		copy.keltnerReversion = new StrategyToggle(safe.keltnerReversion.enabled, safe.keltnerReversion.maxTradesPerDay);
		copy.microScalp = new StrategyToggle(safe.microScalp.enabled, safe.microScalp.maxTradesPerDay);
		copy.enableEarlySweep = safe.enableEarlySweep;
		copy.enableLateSweep = safe.enableLateSweep;
		copy.enableSweepSecondChance = safe.enableSweepSecondChance;
		copy.enableOrbRetest = safe.enableOrbRetest;
		copy.allowOrbRetestLongs = safe.allowOrbRetestLongs;
		copy.allowOrbRetestShorts = safe.allowOrbRetestShorts;
		copy.orbRetestStartMinutes = safe.orbRetestStartMinutes;
		copy.orbRetestEndMinutes = safe.orbRetestEndMinutes;
		copy.enableCompressedOrbBreakout = safe.enableCompressedOrbBreakout;
		copy.skipMidmorningOrbRetest = safe.skipMidmorningOrbRetest;
		copy.requireHigherTimeframeGuard = safe.requireHigherTimeframeGuard;
		copy.allowShorts = safe.allowShorts;
		copy.openingMomentumRangeMinutes = safe.openingMomentumRangeMinutes;
		copy.openingMomentumMaxHoldBars = safe.openingMomentumMaxHoldBars;
		copy.openingMomentumVolumeRatio = safe.openingMomentumVolumeRatio;
		copy.openingMomentumRewardRisk = safe.openingMomentumRewardRisk;
		copy.earlySweepReclaimTicks = safe.earlySweepReclaimTicks;
		copy.lateSweepReclaimTicks = safe.lateSweepReclaimTicks;
		copy.sweepCloseLocation = safe.sweepCloseLocation;
		copy.lateSweepCloseLocation = safe.lateSweepCloseLocation;
		copy.minBodyPct = safe.minBodyPct;
		copy.vwapMinVolumeRatio = safe.vwapMinVolumeRatio;
		copy.vwapMinTrendSlopeTicks = safe.vwapMinTrendSlopeTicks;
		copy.vwapMaxDistanceTicks = safe.vwapMaxDistanceTicks;
		copy.vwapMaxRiskTicks = safe.vwapMaxRiskTicks;
		copy.meanReversionMinDistanceTicks = safe.meanReversionMinDistanceTicks;
		copy.meanReversionOversoldRsi = safe.meanReversionOversoldRsi;
		copy.meanReversionOverboughtRsi = safe.meanReversionOverboughtRsi;
		copy.minRewardRisk = safe.minRewardRisk;
		copy.allowCloseMomentumLongs = safe.allowCloseMomentumLongs;
		copy.allowCloseMomentumShorts = safe.allowCloseMomentumShorts;
		copy.closeMomentumMinMoveTicks = safe.closeMomentumMinMoveTicks;
		copy.closeMomentumVolumeRatio = safe.closeMomentumVolumeRatio;
		copy.closeMomentumRewardRisk = safe.closeMomentumRewardRisk;
		copy.orbCompressedMaxRiskTicks = safe.orbCompressedMaxRiskTicks;
		copy.orbRetestMaxRiskTicks = safe.orbRetestMaxRiskTicks;
		copy.afternoonMinVolumeRatio = safe.afternoonMinVolumeRatio;
		copy.afternoonMaxRiskTicks = safe.afternoonMaxRiskTicks;
		copy.afternoonRewardRisk = safe.afternoonRewardRisk;
		copy.marketIntradayMomentumMinOpenMoveTicks = safe.marketIntradayMomentumMinOpenMoveTicks;
		copy.marketIntradayMomentumMinLateMoveTicks = safe.marketIntradayMomentumMinLateMoveTicks;
		copy.marketIntradayMomentumMinVolumeRatio = safe.marketIntradayMomentumMinVolumeRatio;
		copy.marketIntradayMomentumMaxRiskTicks = safe.marketIntradayMomentumMaxRiskTicks;
		copy.marketIntradayMomentumRewardRisk = safe.marketIntradayMomentumRewardRisk;
		copy.allowKeltnerScalpLongs = safe.allowKeltnerScalpLongs;
		copy.allowKeltnerScalpShorts = safe.allowKeltnerScalpShorts;
		copy.keltnerAtrMultiplier = safe.keltnerAtrMultiplier;
		copy.keltnerMinVolumeRatio = safe.keltnerMinVolumeRatio;
		copy.keltnerMaxRiskTicks = safe.keltnerMaxRiskTicks;
		copy.keltnerRewardRisk = safe.keltnerRewardRisk;
		copy.keltnerMinBodyPct = safe.keltnerMinBodyPct;
		copy.keltnerMinTrendSlopeTicks = safe.keltnerMinTrendSlopeTicks;
		copy.keltnerMinBandWidthTicks = safe.keltnerMinBandWidthTicks;
		copy.keltnerMaxHoldBars = safe.keltnerMaxHoldBars;
		copy.keltnerBucketMinutes = safe.keltnerBucketMinutes;
		copy.microScalpMinVolumeRatio = safe.microScalpMinVolumeRatio;
		copy.microScalpMaxRiskTicks = safe.microScalpMaxRiskTicks;
		copy.microScalpRewardRisk = safe.microScalpRewardRisk;
		copy.microScalpMinBodyPct = safe.microScalpMinBodyPct;
		copy.microScalpMinTrendSlopeTicks = safe.microScalpMinTrendSlopeTicks;
		copy.microScalpMaxHoldBars = safe.microScalpMaxHoldBars;
		copy.microScalpBucketMinutes = safe.microScalpBucketMinutes;
		copy.maxInitialRiskTicks = safe.maxInitialRiskTicks;
		copy.enableAdaptiveExits = safe.enableAdaptiveExits;
		copy.adaptiveMinVolumeRatio = safe.adaptiveMinVolumeRatio;
		copy.adaptiveMinBodyPct = safe.adaptiveMinBodyPct;
		copy.adaptiveTrendTargetBoost = safe.adaptiveTrendTargetBoost;
		copy.adaptiveVolumeTargetBoost = safe.adaptiveVolumeTargetBoost;
		copy.adaptiveBodyTargetBoost = safe.adaptiveBodyTargetBoost;
		copy.adaptiveMaxRewardRisk = safe.adaptiveMaxRewardRisk;
		copy.enableEarlyLossCut = safe.enableEarlyLossCut;
		copy.earlyLossCutBars = safe.earlyLossCutBars;
		copy.earlyLossCutR = safe.earlyLossCutR;
		copy.earlyLossCutMinFavorableR = safe.earlyLossCutMinFavorableR;
		copy.openMaeRiskMultiplier = safe.openMaeRiskMultiplier;
		return copy;
	}

	private static void appendLabResult(StringBuilder json, String variantName, BacktestResult result) {
		Map<String, SegmentStats> monthly = monthlyStats(result.tradeRecords);
		double worstMonth = 0.0;
		double monthlyTotal = 0.0;
		int monthCount = 0;
		int positiveMonths = 0;
		for (String key : monthly.keySet()) {
			SegmentStats stats = monthly.get(key);
			worstMonth = monthCount == 0 ? stats.pnl : Math.min(worstMonth, stats.pnl);
			monthlyTotal += stats.pnl;
			if (stats.pnl > 0.0) {
				positiveMonths++;
			}
			monthCount++;
		}
		double avgMonthly = monthCount == 0 ? 0.0 : monthlyTotal / monthCount;
		double monthlyConsistency = monthCount == 0 ? 0.0 : (positiveMonths * 100.0) / monthCount;
		double score = result.totalProfit + (result.profitFactor * 250.0) + (result.trades * 3.0) + (avgMonthly * 2.0) + (worstMonth * 1.5) - (result.maxDrawdownPct * 500.0);
		json.append("{")
			.append("\"variant\":").append(jsonString(variantName)).append(",")
			.append("\"symbol\":").append(jsonString(result.symbol)).append(",")
			.append("\"profit\":").append(result.totalProfit).append(",")
			.append("\"trades\":").append(result.trades).append(",")
			.append("\"winRate\":").append(result.winRate).append(",")
			.append("\"profitFactor\":").append(result.profitFactor).append(",")
			.append("\"maxDrawdownPct\":").append(result.maxDrawdownPct).append(",")
			.append("\"avgMonthlyPnl\":").append(round(avgMonthly)).append(",")
			.append("\"worstMonthPnl\":").append(round(worstMonth)).append(",")
			.append("\"positiveMonthPct\":").append(round(monthlyConsistency)).append(",")
			.append("\"score\":").append(round(score)).append(",")
			.append("\"ruleViolation\":").append(result.ruleViolation).append(",")
			.append("\"dataSource\":").append(jsonString(result.dataSource))
			.append("}");
	}

	private static class SegmentStats {
		private double pnl;
		private int trades;
	}

	private static Map<String, SegmentStats> monthlyStats(List<FuturesTrade> trades) {
		Map<String, SegmentStats> stats = new HashMap<String, SegmentStats>();
		for (int index = 0; index < trades.size(); index++) {
			FuturesTrade trade = trades.get(index);
			if (trade.openedAt == null || trade.openedAt.length() < 7) {
				continue;
			}
			String key = trade.openedAt.substring(0, 7);
			SegmentStats segment = stats.get(key);
			if (segment == null) {
				segment = new SegmentStats();
				stats.put(key, segment);
			}
			segment.pnl = round(segment.pnl + trade.pnl);
			segment.trades++;
		}
		return stats;
	}

	public static boolean clearBacktests() {
		initializeStore();
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DELETE FROM FuturesTrades");
			stmt.executeUpdate("DELETE FROM FuturesBacktests");
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static boolean clearPortfolioBacktests() {
		initializeStore();
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("DELETE FROM FuturesPortfolioTrades");
			stmt.executeUpdate("DELETE FROM FuturesPortfolioBacktests");
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static String getLiveStrategySnapshotJson() {
		initializeStore();
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		boolean orderArmed = isOrderSubmissionArmed();
		return "{"
			+ "\"success\":true,"
			+ "\"snapshot\":" + (snapshot == null ? "null" : liveStrategySnapshotJson(snapshot)) + ","
			+ "\"recommendedSourcePortfolioBacktestId\":0,"
			+ "\"practiceAccountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + ","
			+ "\"accountMode\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_MODE) + ","
			+ "\"orderSubmissionArmed\":" + orderArmed + ","
			+ "\"orderArming\":" + getOrderArmingStatusJson() + ","
			+ "\"message\":" + jsonString(snapshot == null
				? "No live strategy configuration is active. Copy Backtest Strategy into the Live Strategy slot before dry-run or practice execution."
				: "Live Strategy configuration is active. Backtest Strategy remains separate for optimization.")
			+ "}";
	}

	public static String getLiveReadinessJson(String symbols, String fundedProfile) {
		initializeStore();
		ProjectXRealtimeManager.initializeStore();
		List<String> requestedSymbols = parseSymbols(cleanOrDefault(symbols, "MES,MNQ,M2K,ES,NQ,MGC,GC"));
		FundedRuleProfile profile = fundedRuleProfileFor(fundedProfile);
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		List<String> snapshotSymbols = snapshot == null ? new ArrayList<String>() : parseSymbols(snapshot.symbols);
		List<String> missingSymbols = new ArrayList<String>();
		for (int index = 0; index < requestedSymbols.size(); index++) {
			String symbol = requestedSymbols.get(index);
			if (!snapshotSymbols.contains(symbol)) {
				missingSymbols.add(symbol);
			}
		}

		boolean liveRunning;
		synchronized (FuturesManager.class) {
			liveRunning = liveSession.running;
		}
		boolean realtimeRunning = ProjectXRealtimeManager.isRunning();
		String configuredAccountId = FuturesConnectionManager.getTopstepxConfiguredAccountId();
		String expectedAccountId = accountIdForFundedProfile(profile.code);
		boolean orderArmed = isOrderSubmissionArmed();
		boolean snapshotActive = snapshot != null;
		boolean snapshotSymbolsOk = snapshotActive && missingSymbols.isEmpty();
		boolean snapshotProfileOk = snapshotActive && profile.code.equals(snapshot.fundedProfile);
		boolean snapshotAccountOk = snapshotActive && expectedAccountId.equals(snapshot.practiceAccountId);
		boolean selectedProfileSupportsReadOnlyFeed = "TOPSTEP_150K_PRACTICE".equals(profile.code);
		boolean configuredPracticeAccountOk = TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(configuredAccountId);
		boolean topstepxOrderPathReady = FuturesConnectionManager.isExecutionProviderReady("TOPSTEPX");
		boolean profileRiskOk = profile.accountSize > 0.0
			&& profile.maxTrailingDrawdown > 0.0
			&& profile.dailyLossLimit > 0.0
			&& profile.maxRiskPerTrade > 0.0
			&& profile.maxAggregateMiniUnits > 0.0;

		int missingCacheCount = 0;
		StringBuilder rowsBySymbol = new StringBuilder("{");
		for (int index = 0; index < requestedSymbols.size(); index++) {
			if (index > 0) {
				rowsBySymbol.append(",");
			}
			String symbol = requestedSymbols.get(index);
			int rows = countCsvDataRows(new File(DATA_DIR + "/" + TIMEFRAME_FOLDER + "/" + instrumentFor(symbol).symbol + ".csv"));
			if (rows <= 0) {
				missingCacheCount++;
			}
			rowsBySymbol.append(jsonString(symbol)).append(":").append(rows);
		}
		rowsBySymbol.append("}");

		boolean canStartSimulated = snapshotActive
			&& snapshotSymbolsOk
			&& snapshotProfileOk
			&& snapshotAccountOk
			&& selectedProfileSupportsReadOnlyFeed
			&& configuredPracticeAccountOk
			&& topstepxOrderPathReady
			&& profileRiskOk
			&& !liveRunning;
		boolean canStartPracticeOrders = canStartSimulated && TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(expectedAccountId);
		boolean activePracticeOrdersOk = liveRunning
			&& orderArmed
			&& selectedProfileSupportsReadOnlyFeed
			&& configuredPracticeAccountOk
			&& topstepxOrderPathReady
			&& TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(expectedAccountId);
		String message;
		if (liveRunning) {
			message = activePracticeOrdersOk
				? "Live bot is running. ProjectX prices and TopstepX 150K practice order submission are active for this session."
				: "Live bot is running, but practice order status needs attention.";
		} else if (canStartSimulated) {
			message = canStartPracticeOrders
				? "Ready for you to press Start Live Bot. This starts ProjectX prices, live strategy analysis, and TopstepX 150K practice order submission."
				: "Ready for you to press Start Live Bot. This starts ProjectX prices plus simulated strategy tracking.";
		} else if (!snapshotActive) {
			message = "Copy Backtest Strategy into the Live Strategy slot before starting the live bot.";
		} else if (!snapshotSymbolsOk) {
			message = "Copy Backtest Strategy into the Live Strategy slot so every tracked future is included.";
		} else if (!snapshotProfileOk || !snapshotAccountOk) {
			message = "Copy Backtest Strategy into the Live Strategy slot after choosing the Topstep account so the live configuration matches.";
		} else if (!selectedProfileSupportsReadOnlyFeed || !configuredPracticeAccountOk) {
			message = "ProjectX read-only realtime is currently locked to the 150K practice account in this build.";
		} else if (!topstepxOrderPathReady) {
			message = "TopstepX practice order connection must be saved and tested before Start Live Bot can submit practice orders.";
		} else if (liveRunning) {
			message = "The live runner is already running. Stop it before starting a fresh session.";
		} else {
			message = "Resolve the blocking pre-flight item before starting the live bot.";
		}

		StringBuilder checks = new StringBuilder("[");
		appendReadinessCheck(checks, "live-strategy", "Live Strategy", snapshotActive ? "pass" : "fail",
			snapshotActive ? "Live strategy slot is active." : "No active live strategy configuration.", true);
		appendReadinessCheck(checks, "strategy-symbols", "Tracked Futures", snapshotSymbolsOk ? "pass" : "fail",
			snapshotSymbolsOk ? String.join(",", requestedSymbols) : "Missing from live config: " + String.join(",", missingSymbols), true);
		appendReadinessCheck(checks, "strategy-account", "Selected Account", snapshotProfileOk && snapshotAccountOk ? "pass" : "fail",
			snapshotActive
				? (snapshotProfileOk && snapshotAccountOk ? "Matches account " + expectedAccountId + "." : "Expected " + expectedAccountId + "; live config has " + snapshot.practiceAccountId + ".")
				: "No live config account yet.", true);
		appendReadinessCheck(checks, "projectx-readonly", "Read-only Prices", selectedProfileSupportsReadOnlyFeed && configuredPracticeAccountOk ? "pass" : "fail",
			configuredPracticeAccountOk
				? "TopstepX account " + configuredAccountId + " is ready for read-only prices."
				: "Configured TopstepX account is " + cleanOrDefault(configuredAccountId, "missing") + "; required " + TOPSTEPX_PRACTICE_ACCOUNT_ID + ".", true);
		appendReadinessCheck(checks, "topstepx-practice-orders", "Practice Order Path", topstepxOrderPathReady ? "pass" : "fail",
			topstepxOrderPathReady ? "TopstepX connection test is passing for practice order submission." : "Save and test the TopstepX connection before starting order submission.", true);
		appendReadinessCheck(checks, "funded-rules", "Funded Rules", profileRiskOk ? "pass" : "fail",
			"DLL $" + round(profile.dailyLossLimit) + " | drawdown $" + round(profile.maxTrailingDrawdown) + " | max units " + round(profile.maxAggregateMiniUnits), true);
		appendReadinessCheck(checks, "runner-state", "Runner State", liveRunning ? "pass" : "pass",
			liveRunning ? "Live session is running." : "No live session is running.", true);
		appendReadinessCheck(checks, "market-cache", "Warmup Data", missingCacheCount == 0 ? "pass" : "warn",
			missingCacheCount == 0 ? "Warmup bars are available for every future." : missingCacheCount + " futures have no local warmup bars; live ticks can still arrive after start.", false);
		appendReadinessCheck(checks, "practice-orders", "Practice Orders", canStartPracticeOrders || activePracticeOrdersOk ? "pass" : "fail",
			activePracticeOrdersOk
				? "Practice orders are armed for the active live session."
				: (canStartPracticeOrders ? "Start Live Bot will auto-arm " + TOPSTEPX_PRACTICE_ACCOUNT_ID + " practice order submission." : "Practice orders require the 150K practice account and passing TopstepX connection."), false);
		checks.append("]");

		return "{"
			+ "\"success\":true,"
			+ "\"ready\":" + (canStartSimulated || liveRunning) + ","
			+ "\"canStartSimulated\":" + canStartSimulated + ","
			+ "\"canStartRealOrders\":" + canStartPracticeOrders + ","
			+ "\"orderSubmissionArmed\":" + orderArmed + ","
			+ "\"liveRunning\":" + liveRunning + ","
			+ "\"realtimeRunning\":" + realtimeRunning + ","
			+ "\"selectedProfile\":" + jsonString(profile.code) + ","
			+ "\"selectedProfileName\":" + jsonString(profile.name) + ","
			+ "\"expectedAccountId\":" + jsonString(expectedAccountId) + ","
			+ "\"configuredAccountId\":" + jsonString(configuredAccountId) + ","
			+ "\"requiredRealtimeAccountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + ","
			+ "\"symbols\":" + jsonStringArray(requestedSymbols) + ","
			+ "\"missingSymbols\":" + jsonStringArray(missingSymbols) + ","
			+ "\"warmupRowsBySymbol\":" + rowsBySymbol + ","
			+ "\"checks\":" + checks + ","
			+ "\"message\":" + jsonString(message)
			+ "}";
	}

	private static void appendReadinessCheck(StringBuilder json, String key, String label, String status, String detail, boolean blocking) {
		if (json.length() > 1) {
			json.append(",");
		}
		json.append("{")
			.append("\"key\":").append(jsonString(key)).append(",")
			.append("\"label\":").append(jsonString(label)).append(",")
			.append("\"status\":").append(jsonString(status)).append(",")
			.append("\"detail\":").append(jsonString(detail)).append(",")
			.append("\"blocking\":").append(blocking)
			.append("}");
	}

	public static String getOrderArmingStatusJson() {
		initializeStore();
		OrderArmingState state = loadOrderArmingState();
		boolean accountOk = TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(FuturesConnectionManager.getTopstepxConfiguredAccountId());
		boolean armed = state.armed && accountOk && TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(state.accountId);
		return "{"
			+ "\"success\":true,"
			+ "\"armed\":" + armed + ","
			+ "\"rawArmed\":" + state.armed + ","
			+ "\"accountOk\":" + accountOk + ","
			+ "\"accountId\":" + jsonString(state.accountId) + ","
			+ "\"requiredAccountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + ","
			+ "\"mode\":" + jsonString(state.mode) + ","
			+ "\"armedAt\":" + jsonString(state.armedAt) + ","
			+ "\"disarmedAt\":" + jsonString(state.disarmedAt) + ","
			+ "\"confirmationPhrase\":" + jsonString(PRACTICE_ORDER_ARM_CONFIRMATION) + ","
			+ "\"message\":" + jsonString(armed ? "Practice order submission is armed for account " + TOPSTEPX_PRACTICE_ACCOUNT_ID + "." : state.message)
			+ "}";
	}

	public static String armPracticeOrders(String accountId, String confirmation) {
		initializeStore();
		String configuredAccountId = FuturesConnectionManager.getTopstepxConfiguredAccountId();
		if (!TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(accountId)) {
			return "{\"success\":false,\"message\":" + jsonString("Practice order arming is locked to account " + TOPSTEPX_PRACTICE_ACCOUNT_ID + ".") + ",\"status\":" + getOrderArmingStatusJson() + "}";
		}
		if (!TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(configuredAccountId)) {
			return "{\"success\":false,\"message\":" + jsonString("Configured TopstepX account is " + cleanOrDefault(configuredAccountId, "missing") + "; switch it to " + TOPSTEPX_PRACTICE_ACCOUNT_ID + " first.") + ",\"status\":" + getOrderArmingStatusJson() + "}";
		}
		if (!PRACTICE_ORDER_ARM_CONFIRMATION.equals(cleanOrDefault(confirmation, ""))) {
			return "{\"success\":false,\"message\":\"Exact arming phrase did not match.\",\"status\":" + getOrderArmingStatusJson() + "}";
		}
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		if (snapshot == null) {
			return "{\"success\":false,\"message\":\"Copy Backtest Strategy into the Live Strategy slot before arming orders.\",\"status\":" + getOrderArmingStatusJson() + "}";
		}
		if (!TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(snapshot.practiceAccountId)) {
			return "{\"success\":false,\"message\":\"The active Live Strategy slot is not tied to the 150K practice account.\",\"status\":" + getOrderArmingStatusJson() + "}";
		}
		String now = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		OrderArmingState state = new OrderArmingState();
		state.armed = true;
		state.accountId = TOPSTEPX_PRACTICE_ACCOUNT_ID;
		state.mode = "PRACTICE_ORDERS";
		state.armedAt = now;
		state.disarmedAt = "";
		state.message = "Practice order submission is armed for account " + TOPSTEPX_PRACTICE_ACCOUNT_ID + ".";
		saveOrderArmingState(state);
		recordLiveAudit("ORDER_ARMED", "WARN", "Practice order submission armed for TopstepX account " + TOPSTEPX_PRACTICE_ACCOUNT_ID, "{\"accountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + "}");
		return "{\"success\":true,\"message\":\"Practice order submission is armed.\",\"status\":" + getOrderArmingStatusJson() + "}";
	}

	public static String disarmPracticeOrders(String reason) {
		initializeStore();
		String now = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		OrderArmingState state = new OrderArmingState();
		state.armed = false;
		state.accountId = TOPSTEPX_PRACTICE_ACCOUNT_ID;
		state.mode = "GUARDED";
		state.armedAt = "";
		state.disarmedAt = now;
		state.message = cleanOrDefault(reason, "Practice order submission is guarded.");
		saveOrderArmingState(state);
		recordLiveAudit("ORDER_DISARMED", "INFO", state.message, "{\"accountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + "}");
		return "{\"success\":true,\"message\":\"Practice order submission is guarded.\",\"status\":" + getOrderArmingStatusJson() + "}";
	}

	private static boolean isOrderSubmissionArmed() {
		OrderArmingState state = loadOrderArmingState();
		return state.armed
			&& TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(state.accountId)
			&& TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(FuturesConnectionManager.getTopstepxConfiguredAccountId());
	}

	private static OrderArmingState loadOrderArmingState() {
		OrderArmingState state = new OrderArmingState();
		String sql = "SELECT * FROM FuturesLiveOrderArming WHERE stateID = 1";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			if (rs.next()) {
				state.armed = rs.getInt("armed") == 1;
				state.accountId = cleanOrDefault(rs.getString("accountId"), TOPSTEPX_PRACTICE_ACCOUNT_ID);
				state.mode = cleanOrDefault(rs.getString("mode"), state.armed ? "PRACTICE_ORDERS" : "GUARDED");
				state.armedAt = cleanOrDefault(rs.getString("armedAt"), "");
				state.disarmedAt = cleanOrDefault(rs.getString("disarmedAt"), "");
				state.message = cleanOrDefault(rs.getString("message"), state.message);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return state;
	}

	private static void saveOrderArmingState(OrderArmingState state) {
		String sql = "INSERT OR REPLACE INTO FuturesLiveOrderArming (stateID, armed, accountId, mode, armedAt, disarmedAt, message) VALUES (1, ?, ?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, state.armed ? 1 : 0);
			pstmt.setString(2, cleanOrDefault(state.accountId, TOPSTEPX_PRACTICE_ACCOUNT_ID));
			pstmt.setString(3, cleanOrDefault(state.mode, state.armed ? "PRACTICE_ORDERS" : "GUARDED"));
			pstmt.setString(4, cleanOrDefault(state.armedAt, ""));
			pstmt.setString(5, cleanOrDefault(state.disarmedAt, ""));
			pstmt.setString(6, cleanOrDefault(state.message, ""));
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static void saveActiveLiveStrategySlot(
		Connection conn,
		Integer sourcePortfolioBacktestId,
		String symbols,
		String fundedProfile,
		String accountMode,
		String accountId,
		int maxOpenPositions,
		int maxAggregateContracts,
		double maxAggregateMiniUnits,
		String strategySettingsJson,
		String riskSettingsJson,
		String portfolioSettingsJson,
		String sourceMetricsJson,
		String codeVersion,
		String now
	) throws SQLException {
		Integer activeSnapshotId = null;
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT snapshotID FROM FuturesLiveStrategySnapshots WHERE active = 1 ORDER BY snapshotID DESC LIMIT 1")) {
			if (rs.next()) {
				activeSnapshotId = rs.getInt("snapshotID");
			}
		}

		if (activeSnapshotId != null) {
			try (PreparedStatement deactivateOthers = conn.prepareStatement("UPDATE FuturesLiveStrategySnapshots SET active = 0, updatedAt = ? WHERE active = 1 AND snapshotID <> ?")) {
				deactivateOthers.setString(1, now);
				deactivateOthers.setInt(2, activeSnapshotId.intValue());
				deactivateOthers.executeUpdate();
			}
			String updateSql = "UPDATE FuturesLiveStrategySnapshots SET "
				+ "sourcePortfolioBacktestID = ?, active = 1, symbols = ?, fundedProfile = ?, accountMode = ?, practiceAccountId = ?, "
				+ "maxOpenPositions = ?, maxAggregateContracts = ?, maxAggregateMiniUnits = ?, strategySettingsJson = ?, riskSettingsJson = ?, "
				+ "portfolioSettingsJson = ?, sourceMetricsJson = ?, codeVersion = ?, updatedAt = ? WHERE snapshotID = ?";
			try (PreparedStatement update = conn.prepareStatement(updateSql)) {
				if (sourcePortfolioBacktestId == null || sourcePortfolioBacktestId.intValue() <= 0) {
					update.setNull(1, Types.INTEGER);
				} else {
					update.setInt(1, sourcePortfolioBacktestId.intValue());
				}
				update.setString(2, symbols);
				update.setString(3, fundedProfile);
				update.setString(4, accountMode);
				update.setString(5, accountId);
				update.setInt(6, maxOpenPositions);
				update.setInt(7, maxAggregateContracts);
				update.setDouble(8, maxAggregateMiniUnits);
				update.setString(9, strategySettingsJson);
				update.setString(10, riskSettingsJson);
				update.setString(11, portfolioSettingsJson);
				update.setString(12, sourceMetricsJson);
				update.setString(13, codeVersion);
				update.setString(14, now);
				update.setInt(15, activeSnapshotId.intValue());
				update.executeUpdate();
			}
			return;
		}

		String insertSql = "INSERT INTO FuturesLiveStrategySnapshots ("
			+ "sourcePortfolioBacktestID, active, symbols, fundedProfile, accountMode, practiceAccountId, "
			+ "maxOpenPositions, maxAggregateContracts, maxAggregateMiniUnits, strategySettingsJson, riskSettingsJson, "
			+ "portfolioSettingsJson, sourceMetricsJson, codeVersion, createdAt, updatedAt"
			+ ") VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
			if (sourcePortfolioBacktestId == null || sourcePortfolioBacktestId.intValue() <= 0) {
				insert.setNull(1, Types.INTEGER);
			} else {
				insert.setInt(1, sourcePortfolioBacktestId.intValue());
			}
			insert.setString(2, symbols);
			insert.setString(3, fundedProfile);
			insert.setString(4, accountMode);
			insert.setString(5, accountId);
			insert.setInt(6, maxOpenPositions);
			insert.setInt(7, maxAggregateContracts);
			insert.setDouble(8, maxAggregateMiniUnits);
			insert.setString(9, strategySettingsJson);
			insert.setString(10, riskSettingsJson);
			insert.setString(11, portfolioSettingsJson);
			insert.setString(12, sourceMetricsJson);
			insert.setString(13, codeVersion);
			insert.setString(14, now);
			insert.setString(15, now);
			insert.executeUpdate();
		}
	}

	public static String updateLiveStrategySnapshotFromPortfolioRun(int sourcePortfolioBacktestId) {
		initializeStore();
		if (sourcePortfolioBacktestId <= 0) {
			return "{\"success\":false,\"message\":\"Choose a valid portfolio backtest run.\"}";
		}
		synchronized (FuturesManager.class) {
			if (liveSession.running) {
				return "{\"success\":false,\"message\":\"Stop the futures live runner before updating the live strategy snapshot.\"}";
			}
		}

		try (Connection conn = DatabaseManager.getConnection()) {
			conn.setAutoCommit(false);
			String selectSql = "SELECT * FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ? LIMIT 1";
			try (PreparedStatement select = conn.prepareStatement(selectSql)) {
				select.setInt(1, sourcePortfolioBacktestId);
				try (ResultSet rs = select.executeQuery()) {
					if (!rs.next()) {
						conn.rollback();
						return "{\"success\":false,\"message\":\"Portfolio backtest run not found.\"}";
					}
					if (rs.getInt("ruleViolation") == 1) {
						conn.rollback();
						return "{\"success\":false,\"message\":\"Rule-violating runs cannot be promoted to the live strategy snapshot.\"}";
					}

					String symbols = cleanSymbolsCsv(rs.getString("symbols"));
					String fundedProfile = cleanOrDefault(rs.getString("fundedProfile"), "CUSTOM");
					String strategySettingsJson = strategySettingsBySymbolJson(symbols);
					String riskSettingsJson = riskSettingsBySymbolJson(symbols);
					String portfolioSettingsJson = livePortfolioSettingsJson(rs);
					String sourceMetricsJson = liveSourceMetricsJson(rs);
					String now = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
					String codeVersion = "local-worktree-" + now.replace(" ", "T");

					saveActiveLiveStrategySlot(
						conn,
						Integer.valueOf(sourcePortfolioBacktestId),
						symbols,
						fundedProfile,
						TOPSTEPX_PRACTICE_ACCOUNT_MODE,
						TOPSTEPX_PRACTICE_ACCOUNT_ID,
						rs.getInt("maxOpenPositions"),
						rs.getInt("maxAggregateContracts"),
						rs.getDouble("maxAggregateMiniUnits"),
						strategySettingsJson,
						riskSettingsJson,
						portfolioSettingsJson,
						sourceMetricsJson,
						codeVersion,
						now
					);
					conn.commit();
					recordLiveAudit("SNAPSHOT_PROMOTED", "INFO", "Live strategy snapshot updated from portfolio run #" + sourcePortfolioBacktestId, sourceMetricsJson);
					LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
					return "{"
						+ "\"success\":true,"
						+ "\"message\":" + jsonString("Live Strategy slot updated from portfolio backtest #" + sourcePortfolioBacktestId + ".") + ","
						+ "\"snapshot\":" + (snapshot == null ? "null" : liveStrategySnapshotJson(snapshot))
						+ "}";
				}
			} catch (SQLException e) {
				conn.rollback();
				throw e;
			} finally {
				conn.setAutoCommit(true);
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return "{\"success\":false,\"message\":\"Failed to update live strategy snapshot.\"}";
		}
	}

	public static String updateLiveStrategySnapshotFromBacktestConfig(String symbols, String fundedProfile) {
		initializeStore();
		List<String> symbolList = parseSymbols(cleanOrDefault(symbols, "MES,MNQ,NQ,MGC,ES,M2K"));
		if (symbolList.isEmpty()) {
			return "{\"success\":false,\"message\":\"Choose at least one futures symbol.\"}";
		}
		synchronized (FuturesManager.class) {
			if (liveSession.running) {
				return "{\"success\":false,\"message\":\"Stop the futures live runner before updating the live strategy configuration.\"}";
			}
		}

		String cleanSymbols = cleanSymbolsCsv(String.join(",", symbolList));
		FundedRuleProfile profile = fundedRuleProfileFor(fundedProfile);
		String accountId = accountIdForFundedProfile(profile.code);
		String accountMode = accountModeForFundedProfile(profile.code);
		String copyResult = copyBacktestStrategyToLive(cleanSymbols);
		if (!copyResult.contains("\"success\":true")) {
			return copyResult;
		}

		String strategySettingsJson = strategySettingsBySymbolJson(cleanSymbols, STRATEGY_SLOT_LIVE);
		String riskSettingsJson = riskSettingsBySymbolJson(cleanSymbols);
		String portfolioSettingsJson = livePortfolioSettingsJson(cleanSymbols, profile, accountId, accountMode);
		String sourceMetricsJson = liveSourceMetricsConfigJson(cleanSymbols, profile);
		String now = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		String codeVersion = "local-worktree-" + now.replace(" ", "T");

		try (Connection conn = DatabaseManager.getConnection()) {
			conn.setAutoCommit(false);
			try {
				saveActiveLiveStrategySlot(
					conn,
					null,
					cleanSymbols,
					profile.code,
					accountMode,
					accountId,
					Math.max(1, profile.maxOpenPositions),
					Math.max(1, profile.maxAggregateContracts),
					profile.maxAggregateMiniUnits,
					strategySettingsJson,
					riskSettingsJson,
					portfolioSettingsJson,
					sourceMetricsJson,
					codeVersion,
					now
				);
				conn.commit();
			} catch (SQLException e) {
				conn.rollback();
				throw e;
			} finally {
				conn.setAutoCommit(true);
			}
			recordLiveAudit("LIVE_STRATEGY_CONFIG_UPDATED", "INFO", "Live Strategy updated from Backtest Strategy for " + cleanSymbols, sourceMetricsJson);
			LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
			return "{"
				+ "\"success\":true,"
				+ "\"message\":" + jsonString("Backtest Strategy copied into the Live Strategy slot for " + cleanSymbols + ".") + ","
				+ "\"snapshot\":" + (snapshot == null ? "null" : liveStrategySnapshotJson(snapshot))
				+ "}";
		} catch (SQLException e) {
			e.printStackTrace();
			return "{\"success\":false,\"message\":\"Failed to update Live Strategy from Backtest Strategy.\"}";
		}
	}

	public static String dryRunTopstepxOrder(
		String symbol,
		String side,
		int contracts,
		double entryPrice,
		double stopPrice,
		double targetPrice,
		String reason
	) {
		initializeStore();
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		if (snapshot == null) {
			return "{\"success\":false,\"message\":\"Promote a live strategy snapshot before constructing dry-run orders.\"}";
		}
		String normalizedSymbol = normalizeSymbol(symbol);
		if (!csvContainsSymbol(snapshot.symbols, normalizedSymbol)) {
			return "{\"success\":false,\"message\":" + jsonString(normalizedSymbol + " is not part of the active live strategy snapshot.") + "}";
		}
		String normalizedSide = normalizeOrderSide(side);
		if (normalizedSide.length() == 0) {
			return "{\"success\":false,\"message\":\"Dry-run order side must be LONG, SHORT, BUY, or SELL.\"}";
		}
		int requestedContracts = boundedInt(contracts, 1, 1, topstepMaxContractsForSymbol(normalizedSymbol));
		double requestedMiniUnits = round(fundedMiniUnitsPerContract(normalizedSymbol) * requestedContracts);
		if (snapshot.maxAggregateMiniUnits > 0.0 && requestedMiniUnits > snapshot.maxAggregateMiniUnits + 0.000001) {
			return "{\"success\":false,\"message\":\"Dry-run order exceeds the active snapshot's funded unit limit.\"}";
		}

		String configuredAccountId = FuturesConnectionManager.getTopstepxConfiguredAccountId();
		String blockReason = "";
		if (!TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(configuredAccountId)) {
			blockReason = "TopstepX selected account must be the 150K practice account " + TOPSTEPX_PRACTICE_ACCOUNT_ID + " during this sprint.";
		}
		String orderType = entryPrice > 0.0 ? "LIMIT" : "MARKET";
		InstrumentSpec spec = instrumentFor(normalizedSymbol);
		double safeEntry = entryPrice > 0.0 ? roundToTick(spec, entryPrice) : 0.0;
		double safeStop = stopPrice > 0.0 ? roundToTick(spec, stopPrice) : 0.0;
		double safeTarget = targetPrice > 0.0 ? roundToTick(spec, targetPrice) : 0.0;
		String createdAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		String requestJson = "{"
			+ "\"provider\":\"TOPSTEPX\","
			+ "\"accountId\":" + jsonString(configuredAccountId) + ","
			+ "\"requiredAccountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + ","
			+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
			+ "\"side\":" + jsonString(normalizedSide) + ","
			+ "\"orderType\":" + jsonString(orderType) + ","
			+ "\"contracts\":" + requestedContracts + ","
			+ "\"fundedMiniUnits\":" + requestedMiniUnits + ","
			+ "\"entryPrice\":" + safeEntry + ","
			+ "\"stopPrice\":" + safeStop + ","
			+ "\"targetPrice\":" + safeTarget + ","
			+ "\"reason\":" + jsonString(cleanOrDefault(reason, "manual dry-run")) + ","
			+ "\"willSubmit\":false"
			+ "}";
		String responseJson = "{"
			+ "\"acceptedForSubmission\":false,"
			+ "\"dryRun\":true,"
			+ "\"orderSubmissionArmed\":false,"
			+ "\"blockReason\":" + jsonString(blockReason.length() == 0 ? "Order submission is disabled; this endpoint only records intent." : blockReason)
			+ "}";
		String status = blockReason.length() == 0 ? "DRY_RUN_RECORDED" : "DRY_RUN_BLOCKED";
		int ledgerId = insertLiveOrderLedger(snapshot.snapshotId, configuredAccountId, normalizedSymbol, normalizedSide, orderType, requestedContracts, safeEntry, safeStop, safeTarget, status, requestJson, responseJson, createdAt);
		recordLiveAudit(status, "INFO", "TopstepX dry-run order intent recorded for " + normalizedSymbol, requestJson);
		return "{"
			+ "\"success\":" + (blockReason.length() == 0) + ","
			+ "\"message\":" + jsonString(blockReason.length() == 0
				? "Dry-run order intent recorded. No broker order was submitted."
				: blockReason) + ","
			+ "\"ledgerId\":" + ledgerId + ","
			+ "\"request\":" + requestJson + ","
			+ "\"response\":" + responseJson
			+ "}";
	}

	public static String submitTopstepxPracticeOrder(
		String symbol,
		String side,
		int contracts,
		double entryPrice,
		double stopPrice,
		double targetPrice,
		String reason
	) {
		initializeStore();
		if (!isOrderSubmissionArmed()) {
			return "{\"success\":false,\"message\":\"Practice order submission is guarded. Arm Practice Orders before broker submission.\"}";
		}
		MarketSessionStatus marketStatus = currentMarketSessionStatus();
		if (!marketStatus.entryWindowOpen) {
			disarmPracticeOrders("Practice order submission was guarded because the regular strategy entry window is closed.");
			return "{\"success\":false,\"message\":" + jsonString("Practice order submission is blocked while " + marketStatus.code + ": " + marketStatus.detail) + "}";
		}
		if (!ProjectXRealtimeManager.isRunning()) {
			disarmPracticeOrders("Practice order submission was guarded because ProjectX realtime is not running.");
			return "{\"success\":false,\"message\":\"Practice order submission is blocked because ProjectX realtime is not running.\"}";
		}
		String lastRealtimeEventAt = ProjectXRealtimeManager.currentLastEventAt();
		LocalDateTime lastRealtimeEvent = parseDisplayLocalDateTime(lastRealtimeEventAt);
		long feedStaleSeconds = lastRealtimeEvent == null ? Long.MAX_VALUE : Duration.between(lastRealtimeEvent, LocalDateTime.now()).getSeconds();
		if (lastRealtimeEvent == null || feedStaleSeconds > 120L) {
			disarmPracticeOrders("Practice order submission was guarded because ProjectX realtime data is stale.");
			return "{\"success\":false,\"message\":" + jsonString("Practice order submission is blocked because ProjectX realtime data is stale. Last event: " + cleanOrDefault(lastRealtimeEventAt, "none")) + "}";
		}
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		if (snapshot == null) {
			return "{\"success\":false,\"message\":\"Copy Backtest Strategy into the Live Strategy slot before submitting practice orders.\"}";
		}
		String normalizedSymbol = normalizeSymbol(symbol);
		if (!csvContainsSymbol(snapshot.symbols, normalizedSymbol)) {
			return "{\"success\":false,\"message\":" + jsonString(normalizedSymbol + " is not part of the active Live Strategy slot.") + "}";
		}
		String normalizedSide = normalizeOrderSide(side);
		if (normalizedSide.length() == 0) {
			return "{\"success\":false,\"message\":\"Order side must be LONG, SHORT, BUY, or SELL.\"}";
		}
		int requestedContracts = boundedInt(contracts, 1, 1, topstepMaxContractsForSymbol(normalizedSymbol));
		double requestedMiniUnits = round(fundedMiniUnitsPerContract(normalizedSymbol) * requestedContracts);
		if (snapshot.maxAggregateMiniUnits > 0.0 && requestedMiniUnits > snapshot.maxAggregateMiniUnits + 0.000001) {
			return "{\"success\":false,\"message\":\"Practice order exceeds the active snapshot's funded unit limit.\"}";
		}
		String configuredAccountId = FuturesConnectionManager.getTopstepxConfiguredAccountId();
		if (!TOPSTEPX_PRACTICE_ACCOUNT_ID.equals(configuredAccountId)) {
			return "{\"success\":false,\"message\":" + jsonString("Configured TopstepX account must be " + TOPSTEPX_PRACTICE_ACCOUNT_ID + " before order submission.") + "}";
		}
		InstrumentSpec spec = instrumentFor(normalizedSymbol);
		double safeEntry = entryPrice > 0.0 ? roundToTick(spec, entryPrice) : 0.0;
		double safeStop = stopPrice > 0.0 ? roundToTick(spec, stopPrice) : 0.0;
		double safeTarget = targetPrice > 0.0 ? roundToTick(spec, targetPrice) : 0.0;
		String orderType = safeEntry > 0.0 ? "LIMIT" : "MARKET";
		String createdAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		String tag = "live-" + normalizedSymbol + "-" + System.currentTimeMillis();
		String requestJson = "{"
			+ "\"provider\":\"TOPSTEPX\","
			+ "\"accountId\":" + jsonString(configuredAccountId) + ","
			+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
			+ "\"side\":" + jsonString(normalizedSide) + ","
			+ "\"orderType\":" + jsonString(orderType) + ","
			+ "\"contracts\":" + requestedContracts + ","
			+ "\"fundedMiniUnits\":" + requestedMiniUnits + ","
			+ "\"entryPrice\":" + safeEntry + ","
			+ "\"stopPrice\":" + safeStop + ","
			+ "\"targetPrice\":" + safeTarget + ","
			+ "\"customTag\":" + jsonString(tag) + ","
			+ "\"reason\":" + jsonString(cleanOrDefault(reason, "live strategy order"))
			+ "}";
		String responseJson = FuturesConnectionManager.submitTopstepxPracticeOrder(
			TOPSTEPX_PRACTICE_ACCOUNT_ID,
			normalizedSymbol,
			normalizedSide,
			requestedContracts,
			safeEntry,
			safeStop,
			safeTarget,
			tag
		);
		boolean success = jsonBoolean(responseJson, "success");
		String status = success ? "SUBMITTED_TOPSTEPX" : "SUBMIT_BLOCKED";
		int ledgerId = insertLiveOrderLedger(snapshot.snapshotId, configuredAccountId, normalizedSymbol, normalizedSide, orderType, requestedContracts, safeEntry, safeStop, safeTarget, status, requestJson, responseJson, createdAt);
		recordLiveAudit(status, success ? "WARN" : "ERROR", (success ? "TopstepX practice order submitted for " : "TopstepX practice order blocked for ") + normalizedSymbol, responseJson);
		return "{"
			+ "\"success\":" + success + ","
			+ "\"message\":" + jsonString(success ? "TopstepX practice order submitted." : "TopstepX practice order was not submitted.") + ","
			+ "\"ledgerId\":" + ledgerId + ","
			+ "\"request\":" + requestJson + ","
			+ "\"response\":" + responseJson
			+ "}";
	}

	public static String runLiveDryRunCycle() {
		initializeStore();
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		if (snapshot == null) {
			return "{\"success\":false,\"message\":\"Promote a live strategy snapshot before running the live dry-run engine.\"}";
		}

		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate.minusDays(90);
		PortfolioBacktestConfig config = livePortfolioConfigFromSnapshot(snapshot, startDate, endDate);
		Map<String, PortfolioSymbolContext> contexts = buildPortfolioContexts(config);
		if (contexts.isEmpty()) {
			return "{\"success\":false,\"message\":\"No local warmup bars are available for the active live snapshot symbols.\"}";
		}
		List<LocalDate> days = portfolioDays(contexts);
		if (days.isEmpty()) {
			return "{\"success\":false,\"message\":\"No completed RTH sessions were found for the active live snapshot symbols.\"}";
		}
		LocalDate replayDay = days.get(days.size() - 1);
		List<LocalTime> times = portfolioTimes(contexts, replayDay);
		if (times.isEmpty()) {
			return "{\"success\":false,\"message\":\"No completed 1-minute bars were found for the latest local replay day.\"}";
		}

		String startedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		int sessionId = createLiveEngineSession(snapshot, "DRY_RUN", "LOCAL_REPLAY", "RUNNING", replayDay.toString(), startedAt);
		int signalCount = 0;
		int acceptedCount = 0;
		int rejectedCount = 0;
		double balance = config.accountSize;
		double dayStartBalance = balance;
		double peakEquity = balance;
		double trailingThreshold = config.accountSize - config.maxTrailingDrawdown;
		boolean stopForDay = false;
		boolean ruleViolation = false;
		String ruleMessage = "";
		Map<String, Integer> takenByStrategy = new HashMap<String, Integer>();
		List<PortfolioPosition> openPositions = new ArrayList<PortfolioPosition>();

		for (int timeIndex = 0; timeIndex < times.size(); timeIndex++) {
			LocalTime time = times.get(timeIndex);
			Map<String, Bar> currentBars = barsAt(contexts, replayDay, time);
			if (currentBars.isEmpty()) {
				continue;
			}

			if (!stopForDay && !ruleViolation) {
				List<SignalEvent> events = signalEventsAt(contexts, replayDay, time);
				rankPortfolioSignalEvents(events, contexts);
				for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
					SignalEvent event = events.get(eventIndex);
					PortfolioSymbolContext context = contexts.get(event.symbol);
					Bar entryBar = currentBars.get(event.symbol);
					if (context == null || entryBar == null) {
						continue;
					}
					signalCount++;
					String rejectReason = "";
					if (hasOpenSymbol(openPositions, event.symbol)) {
						rejectReason = "Rejected: symbol already has an open dry-run position.";
					} else if (openPositions.size() >= config.maxOpenPositions) {
						rejectReason = "Rejected: max open positions reached.";
					}

					int openContracts = openContractCount(openPositions);
					double openMiniUnits = openMiniUnitCount(openPositions);
					if (rejectReason.length() == 0 && (openContracts >= config.maxAggregateContracts || fundedMiniUnitLimitReached(config, openMiniUnits))) {
						rejectReason = "Rejected: aggregate contract or funded-unit limit reached.";
					}

					String strategyKey = event.symbol + "|" + event.signal.strategyCode;
					int taken = countFor(takenByStrategy, strategyKey);
					if (rejectReason.length() == 0 && taken >= maxTradesPerDay(event.signal.strategyCode, context.config.strategySettings)) {
						rejectReason = "Rejected: per-strategy daily limit reached.";
					}

					double equityAtOpen = balance + aggregateOpenPnl(openPositions, currentBars, "open");
					if (rejectReason.length() == 0 && equityAtOpen - dayStartBalance <= -Math.abs(config.dailyLossLimit)) {
						stopForDay = true;
						rejectReason = "Rejected: daily loss guard blocked new entries.";
					}
					if (rejectReason.length() == 0 && equityAtOpen <= trailingThreshold) {
						ruleViolation = true;
						ruleMessage = "Trailing drawdown threshold would be breached before entry.";
						rejectReason = "Rejected: trailing drawdown guard blocked new entries.";
					}

					double reservedOpenRisk = aggregateOpenRisk(openPositions);
					double dailyRiskBudget = Math.abs(config.dailyLossLimit) + (equityAtOpen - dayStartBalance) - reservedOpenRisk;
					double trailingRiskBudget = equityAtOpen - trailingThreshold - reservedOpenRisk;
					double aggregateGuardBudget = Math.min(dailyRiskBudget, trailingRiskBudget);
					double riskBudgetMultiplier = portfolioRiskBudgetMultiplier(
						context,
						event,
						balance - dayStartBalance,
						aggregateGuardBudget,
						openPositions.size()
					);
					double symbolRiskBudget = context.config.maxRiskPerTrade * riskBudgetMultiplier;
					double availableRiskBudget = Math.min(symbolRiskBudget, aggregateGuardBudget);
					int aggregateRoom = config.maxAggregateContracts - openContracts;
					if (config.maxAggregateMiniUnits > 0.0) {
						aggregateRoom = Math.min(aggregateRoom, contractsAllowedByMiniUnitRoom(event.symbol, config.maxAggregateMiniUnits - openMiniUnits));
					}
					if (rejectReason.length() == 0 && (availableRiskBudget <= 0.0 || aggregateRoom <= 0)) {
						rejectReason = "Rejected: no remaining risk or funded-unit room.";
					}

					PortfolioPosition position = null;
					if (rejectReason.length() == 0) {
						position = openPortfolioPosition(context, event, entryBar, availableRiskBudget, aggregateRoom, aggregateGuardBudget);
						if (position == null) {
							rejectReason = "Rejected: signal failed live sizing or risk validation.";
						}
					}

					if (rejectReason.length() == 0 && position != null) {
						position.concurrentPositionsAtEntry = openPositions.size() + 1;
						position.concurrentContractsAtEntry = openContracts + position.contracts;
						openPositions.add(position);
						takenByStrategy.put(strategyKey, taken + 1);
						acceptedCount++;
						insertLiveSignalDecision(sessionId, snapshot.snapshotId, event, position, "ACCEPTED_DRY_RUN", "Dry-run order intent constructed; broker submission disabled.");
					} else {
						rejectedCount++;
						insertLiveSignalDecision(sessionId, snapshot.snapshotId, event, null, "REJECTED", rejectReason);
						if (ruleViolation) {
							insertLiveRiskEvent(sessionId, snapshot.snapshotId, "TRAILING_DRAWDOWN_GUARD", "BLOCK", ruleMessage, decisionPayloadJson(event, null, rejectReason));
							break;
						}
					}
				}
			}

			updateOpenPositionExcursions(openPositions, currentBars);
			double worstOpenPnl = aggregateWorstOpenPnl(openPositions, currentBars);
			double worstEquity = balance + worstOpenPnl;
			double worstIntraday = worstEquity - dayStartBalance;
			if (!openPositions.isEmpty() && worstIntraday <= -Math.abs(config.dailyLossLimit)) {
				ruleViolation = true;
				ruleMessage = "Dry-run daily loss limit breached intratrade.";
				insertLiveRiskEvent(sessionId, snapshot.snapshotId, "DAILY_LOSS_GUARD", "BREACH", ruleMessage, "{\"time\":" + jsonString(replayDay + " " + time) + ",\"worstIntraday\":" + round(worstIntraday) + "}");
			}
			if (!openPositions.isEmpty() && worstEquity <= trailingThreshold) {
				ruleViolation = true;
				ruleMessage = "Dry-run trailing drawdown breached intratrade.";
				insertLiveRiskEvent(sessionId, snapshot.snapshotId, "TRAILING_DRAWDOWN_GUARD", "BREACH", ruleMessage, "{\"time\":" + jsonString(replayDay + " " + time) + ",\"worstEquity\":" + round(worstEquity) + "}");
			}

			List<FuturesTrade> closedTrades = closeTriggeredPortfolioPositions(openPositions, contexts, currentBars, replayDay, time);
			for (int closeIndex = 0; closeIndex < closedTrades.size(); closeIndex++) {
				FuturesTrade trade = closedTrades.get(closeIndex);
				balance = round(balance + trade.pnl);
				insertLiveExitDecision(sessionId, snapshot.snapshotId, trade, "SIMULATED_EXIT", trade.exitReason);
			}

			double currentEquity = balance + aggregateOpenPnl(openPositions, currentBars, "close");
			peakEquity = Math.max(peakEquity, currentEquity);
			double currentIntraday = currentEquity - dayStartBalance;
			if (currentIntraday <= -Math.abs(config.dailyLossLimit)) {
				ruleViolation = true;
				ruleMessage = "Dry-run daily loss limit breached.";
				insertLiveRiskEvent(sessionId, snapshot.snapshotId, "DAILY_LOSS_GUARD", "BREACH", ruleMessage, "{\"time\":" + jsonString(replayDay + " " + time) + ",\"currentIntraday\":" + round(currentIntraday) + "}");
			}
			if (currentEquity <= trailingThreshold) {
				ruleViolation = true;
				ruleMessage = "Dry-run trailing drawdown threshold breached.";
				insertLiveRiskEvent(sessionId, snapshot.snapshotId, "TRAILING_DRAWDOWN_GUARD", "BREACH", ruleMessage, "{\"time\":" + jsonString(replayDay + " " + time) + ",\"currentEquity\":" + round(currentEquity) + "}");
			}
			if (ruleViolation) {
				List<FuturesTrade> forcedTrades = forceClosePortfolioPositions(openPositions, contexts, currentBars, replayDay, time, "Dry-run risk breach flat exit");
				for (int forcedIndex = 0; forcedIndex < forcedTrades.size(); forcedIndex++) {
					FuturesTrade trade = forcedTrades.get(forcedIndex);
					balance = round(balance + trade.pnl);
					insertLiveExitDecision(sessionId, snapshot.snapshotId, trade, "SIMULATED_RISK_FLAT", trade.exitReason);
				}
				break;
			}
			if (stopForDay) {
				break;
			}
		}

		if (!openPositions.isEmpty()) {
			Map<String, Bar> closingBars = lastBarsForDay(contexts, replayDay);
			List<FuturesTrade> forcedTrades = forceClosePortfolioPositions(openPositions, contexts, closingBars, replayDay, times.get(times.size() - 1), "Dry-run end-of-day flat exit");
			for (int forcedIndex = 0; forcedIndex < forcedTrades.size(); forcedIndex++) {
				FuturesTrade trade = forcedTrades.get(forcedIndex);
				balance = round(balance + trade.pnl);
				insertLiveExitDecision(sessionId, snapshot.snapshotId, trade, "SIMULATED_EOD_FLAT", trade.exitReason);
			}
		}

		String lastBarTime = replayDay.toString() + " " + times.get(times.size() - 1).toString();
		String message = ruleViolation
			? ruleMessage
			: "Dry-run live engine replay completed from latest local warmup bars. No broker order was submitted.";
		updateLiveEngineSession(sessionId, ruleViolation ? "RISK_BLOCKED" : "COMPLETED", lastBarTime, acceptedCount + rejectedCount, acceptedCount, rejectedCount, message);
		synchronized (FuturesManager.class) {
			liveSession.sessionId = sessionId;
			liveSession.symbols = snapshot.symbols;
			liveSession.dataMode = "LOCAL_REPLAY";
			liveSession.lastDryRunAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
			liveSession.lastBarTime = lastBarTime;
			liveSession.decisionCount = acceptedCount + rejectedCount;
			liveSession.acceptedDecisionCount = acceptedCount;
			liveSession.rejectedDecisionCount = rejectedCount;
			liveSession.lastUpdatedAt = liveSession.lastDryRunAt;
			liveSession.lastDecision = message;
		}

		return "{"
			+ "\"success\":true,"
			+ "\"message\":" + jsonString(message) + ","
			+ "\"sessionId\":" + sessionId + ","
			+ "\"snapshotId\":" + snapshot.snapshotId + ","
			+ "\"replayDay\":" + jsonString(replayDay.toString()) + ","
			+ "\"lastBarTime\":" + jsonString(lastBarTime) + ","
			+ "\"signals\":" + signalCount + ","
			+ "\"accepted\":" + acceptedCount + ","
			+ "\"rejected\":" + rejectedCount + ","
			+ "\"dataMode\":\"LOCAL_REPLAY\","
			+ "\"decisions\":" + getLiveSignalDecisionsJson(sessionId, 50)
			+ "}";
	}

	public static String getLiveSignalDecisionsJson(int sessionId, int limit) {
		initializeStore();
		int safeLimit = boundedInt(limit, 50, 1, 500);
		StringBuilder json = new StringBuilder("[");
		String sql = sessionId > 0
			? "SELECT * FROM FuturesLiveSignalDecisions WHERE sessionID = ? ORDER BY decisionID DESC LIMIT ?"
			: "SELECT * FROM FuturesLiveSignalDecisions ORDER BY decisionID DESC LIMIT ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			if (sessionId > 0) {
				pstmt.setInt(1, sessionId);
				pstmt.setInt(2, safeLimit);
			} else {
				pstmt.setInt(1, safeLimit);
			}
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					String payload = rs.getString("payloadJson");
					if (json.length() > 1) {
						json.append(",");
					}
					json.append("{")
						.append("\"id\":").append(rs.getInt("decisionID")).append(",")
						.append("\"sessionId\":").append(rs.getInt("sessionID")).append(",")
						.append("\"snapshotId\":").append(rs.getInt("snapshotID")).append(",")
						.append("\"symbol\":").append(jsonString(rs.getString("symbol"))).append(",")
						.append("\"strategyCode\":").append(jsonString(rs.getString("strategyCode"))).append(",")
						.append("\"strategyName\":").append(jsonString(rs.getString("strategyName"))).append(",")
						.append("\"side\":").append(jsonString(rs.getString("side"))).append(",")
						.append("\"signalTime\":").append(jsonString(rs.getString("signalTime"))).append(",")
						.append("\"entryTime\":").append(jsonString(rs.getString("entryTime"))).append(",")
						.append("\"contracts\":").append(rs.getInt("contracts")).append(",")
						.append("\"entryPrice\":").append(round(rs.getDouble("entryPrice"))).append(",")
						.append("\"stopPrice\":").append(round(rs.getDouble("stopPrice"))).append(",")
						.append("\"targetPrice\":").append(round(rs.getDouble("targetPrice"))).append(",")
						.append("\"fundedMiniUnits\":").append(round(rs.getDouble("fundedMiniUnits"))).append(",")
						.append("\"status\":").append(jsonString(rs.getString("status"))).append(",")
						.append("\"pnl\":").append(round(jsonNumber(payload, "pnl", 0.0))).append(",")
						.append("\"mfe\":").append(round(jsonNumber(payload, "mfe", 0.0))).append(",")
						.append("\"mae\":").append(round(jsonNumber(payload, "mae", 0.0))).append(",")
						.append("\"exitPrice\":").append(round(jsonNumber(payload, "exitPrice", 0.0))).append(",")
						.append("\"exitReason\":").append(jsonString(jsonText(payload, "exitReason", ""))).append(",")
						.append("\"reason\":").append(jsonString(rs.getString("reason"))).append(",")
						.append("\"createdAt\":").append(jsonString(rs.getString("createdAt")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	public static String getLiveMetricsJson() {
		initializeStore();
		int sessionId = currentLiveSessionId();
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		double accountSize = jsonNumber(snapshot == null ? "" : snapshot.portfolioSettingsJson, "accountSize", liveSession.accountSize > 0.0 ? liveSession.accountSize : 50000.0);
		int totalDecisions = 0;
		int accepted = 0;
		int rejected = 0;
		int exitTrades = 0;
		int winners = 0;
		double pnl = 0.0;
		double peakPnl = 0.0;
		double maxDrawdown = 0.0;
		String lastUpdated = "";
		String sql = sessionId > 0
			? "SELECT * FROM FuturesLiveSignalDecisions WHERE sessionID = ? ORDER BY decisionID ASC"
			: "SELECT * FROM FuturesLiveSignalDecisions ORDER BY decisionID ASC";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			if (sessionId > 0) {
				pstmt.setInt(1, sessionId);
			}
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					totalDecisions++;
					String status = cleanOrDefault(rs.getString("status"), "");
					if (status.contains("ACCEPTED")) {
						accepted++;
					}
					if (status.contains("REJECTED")) {
						rejected++;
					}
					if (status.startsWith("SIMULATED")) {
						double tradePnl = jsonNumber(rs.getString("payloadJson"), "pnl", 0.0);
						pnl = round(pnl + tradePnl);
						exitTrades++;
						if (tradePnl > 0.0) {
							winners++;
						}
						peakPnl = Math.max(peakPnl, pnl);
						maxDrawdown = Math.max(maxDrawdown, peakPnl - pnl);
					}
					lastUpdated = cleanOrDefault(rs.getString("createdAt"), lastUpdated);
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		double returnPct = accountSize <= 0.0 ? 0.0 : (pnl / accountSize) * 100.0;
		double winRate = exitTrades <= 0 ? 0.0 : (winners * 100.0) / exitTrades;
		int openTrades = Math.max(0, accepted - exitTrades);
		return "{"
			+ "\"success\":true,"
			+ "\"sessionId\":" + sessionId + ","
			+ "\"snapshotId\":" + (snapshot == null ? 0 : snapshot.snapshotId) + ","
			+ "\"symbols\":" + jsonString(snapshot == null ? liveSession.symbols : snapshot.symbols) + ","
			+ "\"currentPnl\":" + round(pnl) + ","
			+ "\"returnPct\":" + round(returnPct) + ","
			+ "\"winRate\":" + round(winRate) + ","
			+ "\"numberOfTrades\":" + exitTrades + ","
			+ "\"openTrades\":" + openTrades + ","
			+ "\"drawdown\":" + round(maxDrawdown) + ","
			+ "\"accepted\":" + accepted + ","
			+ "\"rejected\":" + rejected + ","
			+ "\"decisions\":" + totalDecisions + ","
			+ "\"accountSize\":" + round(accountSize) + ","
			+ "\"lastUpdated\":" + jsonString(lastUpdated)
			+ "}";
	}

	private static int currentLiveSessionId() {
		synchronized (FuturesManager.class) {
			if (liveSession.sessionId > 0) {
				return liveSession.sessionId;
			}
		}
		String sql = "SELECT sessionID FROM FuturesLiveEngineSessions ORDER BY sessionID DESC LIMIT 1";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			if (rs.next()) {
				return rs.getInt(1);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return 0;
	}

	public static String getLiveChartJson(String symbols, int limit) {
		initializeStore();
		List<String> symbolList = parseSymbols(cleanOrDefault(symbols, "MES,MNQ,NQ,MGC,ES,M2K"));
		int safeLimit = boundedInt(limit, 240, 20, 1000);
		LocalDate endDate = LocalDate.now();
		LocalDate startDate = endDate.minusYears(2);
		StringBuilder json = new StringBuilder("{");
		json.append("\"success\":true,")
			.append("\"symbols\":").append(jsonStringArray(symbolList)).append(",")
			.append("\"limit\":").append(safeLimit).append(",")
			.append("\"series\":{");
		for (int symbolIndex = 0; symbolIndex < symbolList.size(); symbolIndex++) {
			if (symbolIndex > 0) {
				json.append(",");
			}
			String symbol = symbolList.get(symbolIndex);
			DataBundle bundle = loadNativeFuturesBars(symbol, startDate, endDate, TIMEFRAME_FOLDER);
			List<Bar> bars = bundle.bars;
			int start = Math.max(0, bars.size() - safeLimit);
			double firstClose = start < bars.size() ? bars.get(start).close : 0.0;
			json.append(jsonString(symbol)).append(":[");
			for (int index = start; index < bars.size(); index++) {
				if (index > start) {
					json.append(",");
				}
				Bar bar = bars.get(index);
				double returnPct = firstClose <= 0.0 ? 0.0 : ((bar.close - firstClose) / firstClose) * 100.0;
				json.append("{")
					.append("\"time\":").append(jsonString(bar.displayTime)).append(",")
					.append("\"close\":").append(round(bar.close)).append(",")
					.append("\"returnPct\":").append(round(returnPct)).append(",")
					.append("\"volume\":").append(round(bar.volume))
					.append("}");
			}
			json.append("]");
		}
		json.append("}}");
		return json.toString();
	}

	public static String getLiveMonitorJson(String symbols, int limit) {
		return getLiveMonitorJson(symbols, limit, "1m");
	}

	public static String getLiveMonitorJson(String symbols, int limit, String timeframe) {
		initializeStore();
		ProjectXRealtimeManager.initializeStore();
		List<String> symbolList = parseSymbols(cleanOrDefault(symbols, "MES,MNQ,M2K,ES,NQ,MGC,GC"));
		String normalizedTimeframe = normalizeLiveMonitorTimeframe(timeframe);
		int defaultLimit = liveMonitorDefaultLimit(normalizedTimeframe);
		int safeLimit = boundedInt(limit, defaultLimit, 40, 2000);
		boolean realtimeRunning = ProjectXRealtimeManager.isRunning();
		String realtimeMode = ProjectXRealtimeManager.currentDataMode();
		String realtimeLastEventAt = ProjectXRealtimeManager.currentLastEventAt();
		String realtimeMessage = ProjectXRealtimeManager.currentLastMessage();
		MarketSessionStatus marketStatus = currentMarketSessionStatus();
		String serverTime = ZonedDateTime.now(NEW_YORK_ZONE).toLocalDateTime().format(DISPLAY_TIME_FORMAT);
		long feedStaleSeconds = secondsSinceDisplayTime(realtimeLastEventAt);
		boolean historyPollingActive = liveMarketFeedActive();
		ensureLiveGraphWarmups(symbolList);
		String graphReadinessJson = liveGraphReadinessJson(symbolList);
		recordLiveMonitorPollSnapshots(symbolList, realtimeRunning);
		boolean hasRealtimeTicks = false;
		boolean hasPollSnapshots = false;
		boolean hasWarmupBars = false;

		StringBuilder marketData = new StringBuilder("{");
		StringBuilder symbolStates = new StringBuilder("[");
		for (int symbolIndex = 0; symbolIndex < symbolList.size(); symbolIndex++) {
			String symbol = symbolList.get(symbolIndex);
			LiveMonitorSeries series = buildLiveMonitorSeries(symbol, new ArrayList<Bar>(), safeLimit, normalizedTimeframe);
			LiveSignalAnalysis analysis = liveSignalSummaryForMonitor(symbol);
			hasRealtimeTicks = hasRealtimeTicks || series.liveEvents > 0;
			hasPollSnapshots = hasPollSnapshots || series.pollEvents > 0;
			hasWarmupBars = hasWarmupBars || series.localBars > 0;
			double changePct = series.firstPrice <= 0.0 ? 0.0 : ((series.lastPrice - series.firstPrice) / series.firstPrice) * 100.0;
			String status;
			if (!realtimeRunning && !historyPollingActive) {
				status = "Not started";
			} else if (!marketStatus.entryWindowOpen) {
				status = marketStatus.label;
			} else if (series.liveEvents > 0 || series.pollEvents > 0) {
				status = "Tracking live candles";
			} else if (series.localBars > 0) {
				status = "Polling ProjectX history";
			} else {
				status = "Waiting for live ticks";
			}

			if (symbolIndex > 0) {
				marketData.append(",");
				symbolStates.append(",");
			}
			marketData.append(jsonString(symbol)).append(":").append(series.pointsJson);
			symbolStates.append("{")
				.append("\"symbol\":").append(jsonString(symbol)).append(",")
				.append("\"dataSource\":").append(jsonString(series.dataSource)).append(",")
				.append("\"analysisStatus\":").append(jsonString(status)).append(",")
				.append("\"lastPrice\":").append(round(series.lastPrice)).append(",")
				.append("\"changePct\":").append(round(changePct)).append(",")
				.append("\"lastBarTime\":").append(jsonString(series.lastTime)).append(",")
				.append("\"bars\":").append(series.localBars).append(",")
				.append("\"liveEvents\":").append(series.liveEvents).append(",")
				.append("\"pollEvents\":").append(series.pollEvents).append(",")
				.append("\"enabledStrategies\":").append(analysis.enabledCount).append(",")
				.append("\"strategyCount\":12,")
				.append("\"activeSignalCount\":").append(analysis.signalCount).append(",")
				.append("\"lastSignalCode\":").append(jsonString(analysis.lastSignalCode)).append(",")
				.append("\"lastSignalName\":").append(jsonString(analysis.lastSignalName)).append(",")
				.append("\"lastSignalSide\":").append(jsonString(analysis.lastSignalSide)).append(",")
				.append("\"lastSignalTime\":").append(jsonString(analysis.lastSignalTime)).append(",")
				.append("\"strategies\":").append(analysis.strategiesJson).append(",")
				.append("\"latestSignals\":").append(analysis.latestSignalsJson)
				.append("}");
		}
		marketData.append("}");
		symbolStates.append("]");
		String source = hasRealtimeTicks || hasPollSnapshots
			? (hasWarmupBars ? "PROJECTX_SIGNALR_WITH_WARMUP" : "PROJECTX_SIGNALR")
			: (hasWarmupBars ? "PROJECTX_HISTORY_WARMUP" : (realtimeRunning ? "PROJECTX_SIGNALR_WAITING" : "LIVE_NOT_STARTED"));
		return "{"
			+ "\"success\":true,"
			+ "\"symbols\":" + jsonStringArray(symbolList) + ","
			+ "\"selectedSymbol\":" + jsonString(symbolList.isEmpty() ? "" : symbolList.get(0)) + ","
			+ "\"timeframe\":" + jsonString(normalizedTimeframe) + ","
			+ "\"timeframeLabel\":" + jsonString(liveMonitorTimeframeLabel(normalizedTimeframe)) + ","
			+ "\"limit\":" + safeLimit + ","
			+ "\"dataSource\":" + jsonString(source) + ","
			+ "\"realtimeRunning\":" + realtimeRunning + ","
			+ "\"historyPolling\":" + historyPollingActive + ","
			+ "\"pollCadenceSeconds\":30,"
			+ "\"realtimeMode\":" + jsonString(realtimeMode) + ","
			+ "\"lastRealtimeEventAt\":" + jsonString(realtimeLastEventAt) + ","
			+ "\"serverTime\":" + jsonString(serverTime) + ","
			+ "\"feedStaleSeconds\":" + feedStaleSeconds + ","
			+ "\"realtimeMessage\":" + jsonString(realtimeMessage) + ","
			+ "\"graphReadiness\":" + graphReadinessJson + ","
			+ "\"marketSession\":" + marketSessionJson(marketStatus) + ","
			+ "\"marketData\":" + marketData + ","
			+ "\"symbolStates\":" + symbolStates
			+ "}";
	}

	private static LiveMonitorSeries buildLiveMonitorSeries(String symbol, List<Bar> bars, int limit, String timeframe) {
		LiveMonitorSeries series = new LiveMonitorSeries();
		LiveWarmupBars warmup = bars == null || bars.isEmpty()
			? liveWarmupBarsForMonitorSymbol(symbol, timeframe, limit)
			: warmupFromBars(bars, "LOCAL_INPUT_BARS");
		List<RealtimeCandle> warmupCandles = realtimeCandlesFromBars(warmup.bars, warmup.dataSource);
		List<RealtimePricePoint> realtimePoints = realtimePricePointsForSymbol(symbol, realtimePointLimitForCandles(limit, timeframe));
		List<RealtimeCandle> realtimeCandles = aggregateRealtimeCandles(realtimePoints, timeframe, limit);
		List<RealtimeCandle> candles = mergeMonitorCandles(warmupCandles, realtimeCandles, limit);
		StringBuilder json = new StringBuilder("[");
		int liveEvents = 0;
		int warmupBars = 0;
		for (int index = 0; index < candles.size(); index++) {
			RealtimeCandle candle = candles.get(index);
			if (index > 0) {
				json.append(",");
			}
			appendMonitorPoint(
				json,
				candle.time,
				candle.open,
				candle.high,
				candle.low,
				candle.close,
				candle.volume,
				candle.vwap,
				candle.ema9,
				candle.ema20,
				candle.rsi14,
				candle.events,
				candle.pollEvents,
				candle.live,
				candle.eventType
			);
			if (series.firstPrice <= 0.0) {
				series.firstPrice = candle.close;
			}
			series.lastPrice = candle.close;
			series.lastTime = candle.time;
			liveEvents += candle.events;
			series.pollEvents += candle.pollEvents;
			if (!candle.live) {
				warmupBars++;
			}
		}
		json.append("]");
		series.pointsJson = json.toString();
		series.localBars = warmupBars;
		series.liveEvents = liveEvents;
		if (liveEvents > 0 || series.pollEvents > 0) {
			series.dataSource = warmupBars > 0 ? "PROJECTX_SIGNALR_WITH_WARMUP" : "PROJECTX_SIGNALR";
		} else if (warmupBars > 0) {
			series.dataSource = warmup.dataSource;
		} else {
			series.dataSource = ProjectXRealtimeManager.isRunning() ? "PROJECTX_SIGNALR_WAITING" : "LIVE_NOT_STARTED";
		}
		return series;
	}

	private static LiveWarmupBars warmupFromBars(List<Bar> bars, String source) {
		LiveWarmupBars warmup = new LiveWarmupBars();
		warmup.bars = copyBars(bars);
		warmup.dataSource = cleanOrDefault(source, "LOCAL_INPUT_BARS");
		warmup.loadedAt = System.currentTimeMillis();
		return warmup;
	}

	private static LiveWarmupBars liveWarmupBarsForSymbol(String symbol, String timeframe, int limit) {
		LiveWarmupBars empty = new LiveWarmupBars();
		if (!liveMarketFeedActive()) {
			return empty;
		}
		String normalizedSymbol = normalizeSymbol(symbol);
		String normalizedTimeframe = normalizeLiveMonitorTimeframe(timeframe);
		String cacheKey = liveWarmupCacheKey(normalizedSymbol, normalizedTimeframe, limit);
		long now = System.currentTimeMillis();
		synchronized (LIVE_WARMUP_CACHE) {
			LiveWarmupBars cached = LIVE_WARMUP_CACHE.get(cacheKey);
			if (cached != null && now - cached.loadedAt <= LIVE_WARMUP_CACHE_TTL_MS) {
				return copyWarmupBars(cached);
			}
		}

		LiveWarmupBars loaded = loadProjectXWarmupBars(normalizedSymbol, normalizedTimeframe, limit);
		synchronized (LIVE_WARMUP_CACHE) {
			LIVE_WARMUP_CACHE.put(cacheKey, copyWarmupBars(loaded));
		}
		return loaded;
	}

	private static LiveWarmupBars liveWarmupBarsForMonitorSymbol(String symbol, String timeframe, int limit) {
		LiveWarmupBars empty = new LiveWarmupBars();
		String normalizedSymbol = normalizeSymbol(symbol);
		String normalizedTimeframe = normalizeLiveMonitorTimeframe(timeframe);
		String cacheKey = liveWarmupCacheKey(normalizedSymbol, normalizedTimeframe, limit);
		long now = System.currentTimeMillis();
		synchronized (LIVE_WARMUP_CACHE) {
			LiveWarmupBars cached = LIVE_WARMUP_CACHE.get(cacheKey);
			if (cached != null && now - cached.loadedAt <= LIVE_WARMUP_CACHE_TTL_MS) {
				return copyWarmupBars(cached);
			}
		}
		empty.dataSource = ProjectXRealtimeManager.isRunning() ? "PROJECTX_SIGNALR_LIVE_ONLY" : "LIVE_NOT_STARTED";
		empty.loadedAt = now;
		return empty;
	}

	private static boolean liveMarketFeedActive() {
		synchronized (FuturesManager.class) {
			if (liveSession.running) {
				return true;
			}
		}
		return ProjectXRealtimeManager.isRunning();
	}

	private static String liveWarmupCacheKey(String symbol, String timeframe, int limit) {
		String startedAt = cleanOrDefault(ProjectXRealtimeManager.currentStartedAt(), "");
		String sessionKey = startedAt.length() > 0 ? startedAt : String.valueOf(currentLiveSessionId());
		return sessionKey + "|" + normalizeSymbol(symbol) + "|" + normalizeLiveMonitorTimeframe(timeframe) + "|" + Math.max(1, limit);
	}

	private static String liveGraphWarmupLoadingKey(String symbol) {
		String startedAt = cleanOrDefault(ProjectXRealtimeManager.currentStartedAt(), "");
		String sessionKey = startedAt.length() > 0 ? startedAt : String.valueOf(currentLiveSessionId());
		return sessionKey + "|" + normalizeSymbol(symbol);
	}

	private static void ensureLiveGraphWarmups(List<String> symbols) {
		if (!liveMarketFeedActive() || symbols == null || symbols.isEmpty()) {
			return;
		}
		for (int index = 0; index < symbols.size(); index++) {
			String symbol = normalizeSymbol(symbols.get(index));
			if (symbol.length() == 0 || liveGraphWarmupReadyForSymbol(symbol)) {
				continue;
			}
			scheduleLiveGraphWarmup(symbol);
		}
	}

	private static boolean liveGraphWarmupReadyForSymbol(String symbol) {
		for (int index = 0; index < LIVE_GRAPH_TIMEFRAMES.length; index++) {
			String timeframe = LIVE_GRAPH_TIMEFRAMES[index];
			if (!liveWarmupCacheReady(symbol, timeframe, liveMonitorDefaultLimit(timeframe))) {
				return false;
			}
		}
		return true;
	}

	private static boolean liveWarmupCacheReady(String symbol, String timeframe, int limit) {
		String cacheKey = liveWarmupCacheKey(symbol, timeframe, limit);
		long now = System.currentTimeMillis();
		synchronized (LIVE_WARMUP_CACHE) {
			LiveWarmupBars cached = LIVE_WARMUP_CACHE.get(cacheKey);
			return cached != null
				&& now - cached.loadedAt <= LIVE_WARMUP_CACHE_TTL_MS
				&& cached.bars != null
				&& !cached.bars.isEmpty()
				&& !cleanOrDefault(cached.dataSource, "").contains("WAITING");
		}
	}

	private static void scheduleLiveGraphWarmup(String symbol) {
		String loadingKey = liveGraphWarmupLoadingKey(symbol);
		synchronized (LIVE_GRAPH_WARMUP_LOADING) {
			if (LIVE_GRAPH_WARMUP_LOADING.contains(loadingKey)) {
				return;
			}
			LIVE_GRAPH_WARMUP_LOADING.add(loadingKey);
		}
		LIVE_GRAPH_WARMUP_EXECUTOR.submit(() -> {
			try {
				loadLiveGraphWarmupBundle(symbol);
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				synchronized (LIVE_GRAPH_WARMUP_LOADING) {
					LIVE_GRAPH_WARMUP_LOADING.remove(loadingKey);
				}
			}
		});
	}

	private static String liveGraphReadinessJson(List<String> symbols) {
		boolean feedActive = liveMarketFeedActive();
		int total = 0;
		int ready = 0;
		int building = 0;
		StringBuilder missing = new StringBuilder("[");
		boolean firstMissing = true;
		long now = System.currentTimeMillis();
		for (int symbolIndex = 0; symbolIndex < symbols.size(); symbolIndex++) {
			String symbol = normalizeSymbol(symbols.get(symbolIndex));
			if (symbol.length() == 0) {
				continue;
			}
			boolean symbolLoading;
			synchronized (LIVE_GRAPH_WARMUP_LOADING) {
				symbolLoading = LIVE_GRAPH_WARMUP_LOADING.contains(liveGraphWarmupLoadingKey(symbol));
			}
			for (int timeframeIndex = 0; timeframeIndex < LIVE_GRAPH_TIMEFRAMES.length; timeframeIndex++) {
				String timeframe = LIVE_GRAPH_TIMEFRAMES[timeframeIndex];
				int limit = liveMonitorDefaultLimit(timeframe);
				String cacheKey = liveWarmupCacheKey(symbol, timeframe, limit);
				LiveWarmupBars cached;
				synchronized (LIVE_WARMUP_CACHE) {
					cached = LIVE_WARMUP_CACHE.get(cacheKey);
				}
				boolean itemReady = cached != null
					&& now - cached.loadedAt <= LIVE_WARMUP_CACHE_TTL_MS
					&& cached.bars != null
					&& !cached.bars.isEmpty()
					&& !cleanOrDefault(cached.dataSource, "").contains("WAITING");
				total++;
				if (itemReady) {
					ready++;
					continue;
				}
				if (symbolLoading) {
					building++;
				}
				if (!firstMissing) {
					missing.append(",");
				}
				firstMissing = false;
				missing.append("{")
					.append("\"symbol\":").append(jsonString(symbol)).append(",")
					.append("\"timeframe\":").append(jsonString(timeframe)).append(",")
					.append("\"status\":").append(jsonString(symbolLoading ? "building" : (feedActive ? "queued" : "idle")))
					.append("}");
			}
		}
		missing.append("]");
		boolean allReady = feedActive && total > 0 && ready == total;
		String status = allReady ? "ready" : (feedActive ? "building" : "idle");
		String message = allReady
			? "Graph history is ready for every tracked futures symbol and timeframe."
			: feedActive
				? "Currently building futures graph history for every symbol and timeframe."
				: "Start the market feed to build futures graph history.";
		return "{"
			+ "\"ready\":" + allReady + ","
			+ "\"status\":" + jsonString(status) + ","
			+ "\"message\":" + jsonString(message) + ","
			+ "\"readyItems\":" + ready + ","
			+ "\"totalItems\":" + total + ","
			+ "\"buildingItems\":" + building + ","
			+ "\"timeframes\":" + jsonStringArray(java.util.Arrays.asList(LIVE_GRAPH_TIMEFRAMES)) + ","
			+ "\"missing\":" + missing
			+ "}";
	}

	private static void loadLiveGraphWarmupBundle(String symbol) {
		String normalizedSymbol = normalizeSymbol(symbol);
		if (normalizedSymbol.length() == 0) {
			return;
		}
		int maxMinuteWindow = 0;
		for (int index = 0; index < LIVE_GRAPH_TIMEFRAMES.length; index++) {
			String timeframe = LIVE_GRAPH_TIMEFRAMES[index];
			int limit = liveMonitorDefaultLimit(timeframe);
			int minutesPerCandle = liveMonitorTimeframeMinutes(timeframe);
			maxMinuteWindow = Math.max(maxMinuteWindow, (limit * minutesPerCandle) + 390);
		}
		int lookbackMinutes = Math.min(43200, Math.max(2880, maxMinuteWindow * 4));
		List<Bar> sourceMinuteBars;
		String dataSource;
		try {
			List<FuturesConnectionManager.TopstepxBarSnapshot> snapshots = FuturesConnectionManager.fetchTopstepxRecentMinuteBars(normalizedSymbol, lookbackMinutes, true);
			sourceMinuteBars = topstepxSnapshotsToBars(normalizedSymbol, snapshots);
			if (sourceMinuteBars.isEmpty()) {
				throw new IllegalStateException("ProjectX returned no warmup bars for " + normalizedSymbol + ".");
			}
			dataSource = "PROJECTX_HISTORY_WARMUP";
		} catch (Exception e) {
			LocalDate endDate = LocalDate.now(NEW_YORK_ZONE);
			DataBundle local = loadNativeFuturesBars(normalizedSymbol, endDate.minusDays(45), endDate, TIMEFRAME_FOLDER);
			sourceMinuteBars = local.bars;
			dataSource = "LOCAL_SESSION_WARMUP";
		}

		Map<String, LiveWarmupBars> loaded = new HashMap<String, LiveWarmupBars>();
		for (int index = 0; index < LIVE_GRAPH_TIMEFRAMES.length; index++) {
			String timeframe = LIVE_GRAPH_TIMEFRAMES[index];
			int limit = liveMonitorDefaultLimit(timeframe);
			loaded.put(liveWarmupCacheKey(normalizedSymbol, timeframe, limit), buildWarmupBarsFromMinuteBars(normalizedSymbol, sourceMinuteBars, timeframe, limit, dataSource));
		}
		synchronized (LIVE_WARMUP_CACHE) {
			LIVE_WARMUP_CACHE.putAll(loaded);
		}
	}

	private static LiveWarmupBars loadProjectXWarmupBars(String symbol, String timeframe, int limit) {
		LiveWarmupBars warmup = new LiveWarmupBars();
		String normalizedTimeframe = normalizeLiveMonitorTimeframe(timeframe);
		int minutesPerCandle = liveMonitorTimeframeMinutes(normalizedTimeframe);
		int requestedBars = Math.max(liveMonitorDefaultLimit(normalizedTimeframe), Math.max(40, limit));
		int rthMinutesNeeded = Math.max(390 * 3, (requestedBars * minutesPerCandle) + 390);
		int lookbackMinutes = Math.min(43200, Math.max(2880, rthMinutesNeeded * 4));
		try {
			List<FuturesConnectionManager.TopstepxBarSnapshot> snapshots = FuturesConnectionManager.fetchTopstepxRecentMinuteBars(symbol, lookbackMinutes, true);
			List<Bar> oneMinuteBars = rollingRthWarmupBars(topstepxSnapshotsToBars(symbol, snapshots), requestedBars * minutesPerCandle + 390);
			List<Bar> timeframeBars = aggregateBarsForTimeframe(oneMinuteBars, normalizedTimeframe, limit);
			enrichLiveBars(timeframeBars, instrumentFor(symbol));
			warmup.bars = selectLastBars(timeframeBars, limit);
			warmup.dataSource = warmup.bars.isEmpty() ? "PROJECTX_SIGNALR_WAITING" : "PROJECTX_HISTORY_WARMUP";
		} catch (Exception e) {
			LocalDate endDate = LocalDate.now(NEW_YORK_ZONE);
			DataBundle local = loadNativeFuturesBars(symbol, endDate.minusDays(45), endDate, TIMEFRAME_FOLDER);
			List<Bar> localOneMinuteBars = rollingRthWarmupBars(local.bars, requestedBars * minutesPerCandle + 390);
			List<Bar> localTimeframeBars = aggregateBarsForTimeframe(localOneMinuteBars, normalizedTimeframe, limit);
			enrichLiveBars(localTimeframeBars, instrumentFor(symbol));
			warmup.bars = selectLastBars(localTimeframeBars, limit);
			warmup.dataSource = warmup.bars.isEmpty() ? "PROJECTX_SIGNALR_WAITING" : "LOCAL_SESSION_WARMUP";
		}
		warmup.loadedAt = System.currentTimeMillis();
		return warmup;
	}

	private static LiveWarmupBars buildWarmupBarsFromMinuteBars(String symbol, List<Bar> minuteBars, String timeframe, int limit, String dataSource) {
		LiveWarmupBars warmup = new LiveWarmupBars();
		String normalizedTimeframe = normalizeLiveMonitorTimeframe(timeframe);
		int minutesPerCandle = liveMonitorTimeframeMinutes(normalizedTimeframe);
		int requestedBars = Math.max(liveMonitorDefaultLimit(normalizedTimeframe), Math.max(40, limit));
		List<Bar> oneMinuteBars = rollingRthWarmupBars(minuteBars, requestedBars * minutesPerCandle + 390);
		List<Bar> timeframeBars = aggregateBarsForTimeframe(oneMinuteBars, normalizedTimeframe, limit);
		enrichLiveBars(timeframeBars, instrumentFor(symbol));
		warmup.bars = selectLastBars(timeframeBars, limit);
		warmup.dataSource = warmup.bars.isEmpty() ? "PROJECTX_SIGNALR_WAITING" : cleanOrDefault(dataSource, "PROJECTX_HISTORY_WARMUP");
		warmup.loadedAt = System.currentTimeMillis();
		return warmup;
	}

	private static List<Bar> topstepxSnapshotsToBars(String symbol, List<FuturesConnectionManager.TopstepxBarSnapshot> snapshots) {
		List<Bar> bars = new ArrayList<Bar>();
		if (snapshots == null || snapshots.isEmpty()) {
			return bars;
		}
		for (int index = 0; index < snapshots.size(); index++) {
			FuturesConnectionManager.TopstepxBarSnapshot snapshot = snapshots.get(index);
			if (snapshot == null || snapshot.timestamp == null || snapshot.close <= 0.0) {
				continue;
			}
			LocalDateTime marketDateTime = snapshot.timestamp.atZone(NEW_YORK_ZONE).toLocalDateTime().withSecond(0).withNano(0);
			Bar bar = new Bar();
			bar.displayTime = marketDateTime.format(DISPLAY_TIME_FORMAT);
			bar.marketDate = marketDateTime.toLocalDate();
			bar.marketTime = marketDateTime.toLocalTime();
			bar.open = snapshot.open;
			bar.high = snapshot.high;
			bar.low = snapshot.low;
			bar.close = snapshot.close;
			bar.volume = snapshot.volume;
			bar.vwap = snapshot.vwap > 0.0 ? snapshot.vwap : ((snapshot.high + snapshot.low + snapshot.close) / 3.0);
			bars.add(bar);
		}
		return selectLastBars(bars, bars.size());
	}

	private static List<Bar> rollingRthWarmupBars(List<Bar> bars, int limit) {
		List<Bar> selected = new ArrayList<Bar>();
		if (bars == null || bars.isEmpty()) {
			return selected;
		}
		ZonedDateTime now = ZonedDateTime.now(NEW_YORK_ZONE);
		LocalDate today = now.toLocalDate();
		LocalTime latestAllowed = now.toLocalTime().plusMinutes(1);
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (bar.marketDate == null || bar.marketTime == null) {
				continue;
			}
			if (bar.marketTime.isBefore(RTH_START) || !bar.marketTime.isBefore(RTH_END)) {
				continue;
			}
			if (today.equals(bar.marketDate) && bar.marketTime.isAfter(latestAllowed)) {
				continue;
			}
			if (bar.marketDate.isAfter(today)) {
				continue;
			}
			selected.add(copyBar(bar));
		}
		return selectLastBars(selected, Math.max(1, limit));
	}

	private static List<RealtimeCandle> realtimeCandlesFromBars(List<Bar> bars, String source) {
		List<RealtimeCandle> candles = new ArrayList<RealtimeCandle>();
		List<Bar> sorted = selectLastBars(bars, bars == null ? 0 : bars.size());
		for (int index = 0; index < sorted.size(); index++) {
			Bar bar = sorted.get(index);
			if (bar == null || bar.close <= 0.0) {
				continue;
			}
			RealtimeCandle candle = new RealtimeCandle();
			candle.time = cleanOrDefault(bar.displayTime, "");
			candle.eventType = cleanOrDefault(source, "PROJECTX_HISTORY_WARMUP");
			candle.open = bar.open;
			candle.high = bar.high;
			candle.low = bar.low;
			candle.close = bar.close;
			candle.volume = bar.volume;
			candle.vwap = bar.vwap;
			candle.ema9 = bar.ema9;
			candle.ema20 = bar.ema20;
			candle.rsi14 = bar.rsi14;
			candle.live = false;
			candles.add(candle);
		}
		return candles;
	}

	private static List<RealtimeCandle> mergeMonitorCandles(List<RealtimeCandle> warmupCandles, List<RealtimeCandle> realtimeCandles, int limit) {
		TreeMap<LocalDateTime, RealtimeCandle> byTime = new TreeMap<LocalDateTime, RealtimeCandle>();
		mergeMonitorCandleList(byTime, warmupCandles);
		mergeMonitorCandleList(byTime, realtimeCandles);
		List<RealtimeCandle> all = new ArrayList<RealtimeCandle>(byTime.values());
		int start = Math.max(0, all.size() - Math.max(1, limit));
		List<RealtimeCandle> selected = new ArrayList<RealtimeCandle>();
		for (int index = start; index < all.size(); index++) {
			selected.add(all.get(index));
		}
		enrichRealtimeCandles(selected);
		return selected;
	}

	private static void mergeMonitorCandleList(TreeMap<LocalDateTime, RealtimeCandle> byTime, List<RealtimeCandle> candles) {
		if (candles == null || candles.isEmpty()) {
			return;
		}
		for (int index = 0; index < candles.size(); index++) {
			RealtimeCandle source = candles.get(index);
			LocalDateTime time = parseDisplayLocalDateTime(source.time);
			if (time == null || source.close <= 0.0) {
				continue;
			}
			RealtimeCandle existing = byTime.get(time);
			if (existing == null) {
				byTime.put(time, copyRealtimeCandle(source));
				continue;
			}
			existing.open = existing.open > 0.0 ? existing.open : source.open;
			existing.high = Math.max(existing.high, source.high);
			existing.low = Math.min(existing.low, source.low);
			existing.close = source.close;
			existing.volume = Math.max(existing.volume, source.volume);
			existing.events += source.events;
			existing.pollEvents += source.pollEvents;
			existing.live = existing.live || source.live;
			if (source.live || existing.eventType == null || existing.eventType.length() == 0) {
				existing.eventType = cleanOrDefault(source.eventType, existing.eventType);
			}
		}
	}

	private static RealtimeCandle copyRealtimeCandle(RealtimeCandle source) {
		RealtimeCandle copy = new RealtimeCandle();
		if (source == null) {
			return copy;
		}
		copy.time = source.time;
		copy.eventType = source.eventType;
		copy.open = source.open;
		copy.high = source.high;
		copy.low = source.low;
		copy.close = source.close;
		copy.volume = source.volume;
		copy.vwap = source.vwap;
		copy.ema9 = source.ema9;
		copy.ema20 = source.ema20;
		copy.rsi14 = source.rsi14;
		copy.events = source.events;
		copy.pollEvents = source.pollEvents;
		copy.live = source.live;
		return copy;
	}

	private static List<Bar> aggregateBarsForTimeframe(List<Bar> bars, String timeframe, int limit) {
		List<Bar> source = selectLastBars(bars, bars == null ? 0 : bars.size());
		if (source.isEmpty()) {
			return source;
		}
		String normalized = normalizeLiveMonitorTimeframe(timeframe);
		if ("1m".equals(normalized)) {
			return selectLastBars(source, limit);
		}
		TreeMap<LocalDateTime, Bar> byBucket = new TreeMap<LocalDateTime, Bar>();
		for (int index = 0; index < source.size(); index++) {
			Bar bar = source.get(index);
			if (bar.marketDate == null || bar.marketTime == null || bar.close <= 0.0) {
				continue;
			}
			LocalDateTime bucket = realtimeCandleBucket(LocalDateTime.of(bar.marketDate, bar.marketTime), normalized);
			Bar aggregate = byBucket.get(bucket);
			if (aggregate == null) {
				aggregate = new Bar();
				aggregate.displayTime = bucket.format(DISPLAY_TIME_FORMAT);
				aggregate.marketDate = bucket.toLocalDate();
				aggregate.marketTime = bucket.toLocalTime();
				aggregate.open = bar.open;
				aggregate.high = bar.high;
				aggregate.low = bar.low;
				aggregate.close = bar.close;
				aggregate.volume = bar.volume;
				aggregate.vwap = bar.vwap;
				byBucket.put(bucket, aggregate);
				continue;
			}
			double previousVolume = Math.max(0.0, aggregate.volume);
			double barVolume = Math.max(0.0, bar.volume);
			aggregate.high = Math.max(aggregate.high, bar.high);
			aggregate.low = Math.min(aggregate.low, bar.low);
			aggregate.close = bar.close;
			aggregate.volume += bar.volume;
			if (previousVolume + barVolume > 0.0) {
				aggregate.vwap = ((aggregate.vwap * previousVolume) + (bar.vwap * barVolume)) / (previousVolume + barVolume);
			}
		}
		return selectLastBars(new ArrayList<Bar>(byBucket.values()), limit);
	}

	private static List<Bar> mergeBarSeries(List<Bar> warmupBars, List<Bar> liveBars, int limit) {
		TreeMap<LocalDateTime, Bar> byTime = new TreeMap<LocalDateTime, Bar>();
		mergeBarList(byTime, warmupBars, false);
		mergeBarList(byTime, liveBars, true);
		return selectLastBars(new ArrayList<Bar>(byTime.values()), limit);
	}

	private static void mergeBarList(TreeMap<LocalDateTime, Bar> byTime, List<Bar> bars, boolean live) {
		if (bars == null || bars.isEmpty()) {
			return;
		}
		for (int index = 0; index < bars.size(); index++) {
			Bar source = bars.get(index);
			if (source == null || source.marketDate == null || source.marketTime == null || source.close <= 0.0) {
				continue;
			}
			LocalDateTime time = LocalDateTime.of(source.marketDate, source.marketTime).withSecond(0).withNano(0);
			Bar existing = byTime.get(time);
			if (existing == null) {
				byTime.put(time, copyBar(source));
				continue;
			}
			existing.open = existing.open > 0.0 ? existing.open : source.open;
			existing.high = Math.max(existing.high, source.high);
			existing.low = Math.min(existing.low, source.low);
			existing.close = source.close;
			existing.volume = live ? Math.max(existing.volume, source.volume) : existing.volume + source.volume;
			existing.vwap = source.vwap > 0.0 ? source.vwap : existing.vwap;
		}
	}

	private static void enrichLiveBars(List<Bar> bars, InstrumentSpec spec) {
		if (bars == null || bars.isEmpty()) {
			return;
		}
		double cumulativeVolume = 0.0;
		double cumulativeTypicalVolume = 0.0;
		double ema9 = 0.0;
		double ema20 = 0.0;
		double ema50 = 0.0;
		List<Double> closes = new ArrayList<Double>();
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			double volume = bar.volume > 0.0 ? bar.volume : 1.0;
			double typical = (bar.high + bar.low + bar.close) / 3.0;
			cumulativeVolume += volume;
			cumulativeTypicalVolume += typical * volume;
			bar.vwap = cumulativeVolume > 0.0 ? cumulativeTypicalVolume / cumulativeVolume : bar.close;
			ema9 = nextEma(ema9, bar.close, 9);
			ema20 = nextEma(ema20, bar.close, 20);
			ema50 = nextEma(ema50, bar.close, 50);
			bar.ema9 = ema9;
			bar.ema20 = ema20;
			bar.ema50 = ema50;
			closes.add(bar.close);
			bar.rsi14 = realtimeRsi(closes, RSI_PERIOD);
			bar.bodyPct = defaultBodyPct(bar);
			if (spec.tickSize > 0.0) {
				bar.rangeTicks = Math.abs(bar.high - bar.low) / spec.tickSize;
			}
		}
		applyRealtimeBarDerivedFields(bars, spec);
	}

	private static List<Bar> selectLastBars(List<Bar> bars, int limit) {
		List<Bar> selected = copyBars(bars);
		Collections.sort(selected, new Comparator<Bar>() {
			@Override
			public int compare(Bar first, Bar second) {
				LocalDateTime firstTime = first.marketDate == null || first.marketTime == null ? LocalDateTime.MIN : LocalDateTime.of(first.marketDate, first.marketTime);
				LocalDateTime secondTime = second.marketDate == null || second.marketTime == null ? LocalDateTime.MIN : LocalDateTime.of(second.marketDate, second.marketTime);
				return firstTime.compareTo(secondTime);
			}
		});
		if (limit <= 0 || selected.size() <= limit) {
			return selected;
		}
		return new ArrayList<Bar>(selected.subList(selected.size() - limit, selected.size()));
	}

	private static List<Bar> copyBars(List<Bar> bars) {
		List<Bar> copies = new ArrayList<Bar>();
		if (bars == null || bars.isEmpty()) {
			return copies;
		}
		for (int index = 0; index < bars.size(); index++) {
			copies.add(copyBar(bars.get(index)));
		}
		return copies;
	}

	private static Bar copyBar(Bar source) {
		Bar copy = new Bar();
		if (source == null) {
			return copy;
		}
		copy.displayTime = source.displayTime;
		copy.marketDate = source.marketDate;
		copy.marketTime = source.marketTime;
		copy.open = source.open;
		copy.high = source.high;
		copy.low = source.low;
		copy.close = source.close;
		copy.volume = source.volume;
		copy.vwap = source.vwap;
		copy.ema9 = source.ema9;
		copy.ema20 = source.ema20;
		copy.ema50 = source.ema50;
		copy.atr14 = source.atr14;
		copy.rsi14 = source.rsi14;
		copy.volumeSma20 = source.volumeSma20;
		copy.rangeTicks = source.rangeTicks;
		copy.bodyPct = source.bodyPct;
		return copy;
	}

	private static LiveWarmupBars copyWarmupBars(LiveWarmupBars source) {
		LiveWarmupBars copy = new LiveWarmupBars();
		if (source == null) {
			return copy;
		}
		copy.bars = copyBars(source.bars);
		copy.dataSource = source.dataSource;
		copy.loadedAt = source.loadedAt;
		return copy;
	}

	private static void recordLiveMonitorPollSnapshots(List<String> symbols, boolean realtimeRunning) {
		if (!realtimeRunning || symbols == null || symbols.isEmpty()) {
			return;
		}
		String bucketTime = ZonedDateTime.now(NEW_YORK_ZONE).toLocalDateTime().withSecond(0).withNano(0).format(DISPLAY_TIME_FORMAT);
		for (int index = 0; index < symbols.size(); index++) {
			String symbol = normalizeSymbol(symbols.get(index));
			if (symbol.length() == 0 || marketEventExistsForMinute(symbol, bucketTime)) {
				continue;
			}
			RealtimePricePoint latest = latestRealtimePricePointForSymbol(symbol);
			if (latest == null || latest.price <= 0.0) {
				continue;
			}
			insertLiveMonitorPollSnapshot(symbol, latest, bucketTime);
		}
	}

	private static boolean marketEventExistsForMinute(String symbol, String bucketTime) {
		String sql = "SELECT 1 FROM FuturesLiveRealtimeEvents WHERE hub = 'market' AND symbol = ? AND receivedAt = ? LIMIT 1";
		try (Connection conn = ProjectXRealtimeManager.openRealtimeConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeSymbol(symbol));
			pstmt.setString(2, cleanOrDefault(bucketTime, ""));
			try (ResultSet rs = pstmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return true;
		}
	}

	private static RealtimePricePoint latestRealtimePricePointForSymbol(String symbol) {
		List<RealtimePricePoint> points = realtimePricePointsForSymbol(symbol, 1000);
		return points.isEmpty() ? null : points.get(points.size() - 1);
	}

	private static void insertLiveMonitorPollSnapshot(String symbol, RealtimePricePoint latest, String receivedAt) {
		String normalizedSymbol = normalizeSymbol(symbol);
		String payloadJson = "{"
			+ "\"symbolName\":" + jsonString("/" + normalizedSymbol) + ","
			+ "\"symbol\":" + jsonString("F.US." + normalizedSymbol) + ","
			+ "\"lastPrice\":" + round(latest.price) + ","
			+ "\"volume\":0,"
			+ "\"poll\":true,"
			+ "\"sourceEventType\":" + jsonString(latest.eventType) + ","
			+ "\"sourceEventTime\":" + jsonString(latest.time) + ","
			+ "\"timestamp\":" + jsonString(ZonedDateTime.now(NEW_YORK_ZONE).toOffsetDateTime().toString())
			+ "}";
		String sql = "INSERT INTO FuturesLiveRealtimeEvents (hub, eventType, accountId, contractId, symbol, payloadJson, receivedAt) VALUES ('market', 'LIVE_POLL_SNAPSHOT', '', '', ?, ?, ?)";
		try (Connection conn = ProjectXRealtimeManager.openRealtimeConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizedSymbol);
			pstmt.setString(2, payloadJson);
			pstmt.setString(3, cleanOrDefault(receivedAt, ""));
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static int realtimePointLimitForCandles(int candleLimit, String timeframe) {
		String normalized = normalizeLiveMonitorTimeframe(timeframe);
		int multiplier = "1h".equals(normalized) ? 360 : ("30m".equals(normalized) ? 240 : ("15m".equals(normalized) ? 180 : ("5m".equals(normalized) ? 80 : 16)));
		return boundedInt(candleLimit * multiplier, 1200, candleLimit, 5000);
	}

	private static List<RealtimeCandle> aggregateRealtimeCandles(List<RealtimePricePoint> points, String timeframe, int limit) {
		TreeMap<LocalDateTime, RealtimeCandle> byBucket = new TreeMap<LocalDateTime, RealtimeCandle>();
		for (int index = 0; index < points.size(); index++) {
			RealtimePricePoint point = points.get(index);
			if (point.price <= 0.0) {
				continue;
			}
			LocalDateTime eventTime = parseDisplayLocalDateTime(point.time);
			if (eventTime == null) {
				continue;
			}
			LocalDateTime bucket = realtimeCandleBucket(eventTime, timeframe);
			RealtimeCandle candle = byBucket.get(bucket);
			if (candle == null) {
				candle = new RealtimeCandle();
				candle.time = bucket.format(DISPLAY_TIME_FORMAT);
				candle.eventType = cleanOrDefault(point.eventType, "LIVE");
				candle.open = point.price;
				candle.high = point.price;
				candle.low = point.price;
				candle.close = point.price;
				candle.live = true;
				byBucket.put(bucket, candle);
			}
			candle.high = Math.max(candle.high, point.price);
			candle.low = Math.min(candle.low, point.price);
			candle.close = point.price;
			candle.volume += point.volume > 0.0 ? point.volume : 1.0;
			if (point.pollSnapshot) {
				candle.pollEvents++;
				if (candle.events <= 0) {
					candle.eventType = cleanOrDefault(point.eventType, candle.eventType);
				}
			} else {
				candle.events++;
				candle.eventType = cleanOrDefault(point.eventType, candle.eventType);
			}
		}

		List<RealtimeCandle> allCandles = new ArrayList<RealtimeCandle>(byBucket.values());
		int start = Math.max(0, allCandles.size() - Math.max(1, limit));
		List<RealtimeCandle> selected = new ArrayList<RealtimeCandle>();
		for (int index = start; index < allCandles.size(); index++) {
			selected.add(allCandles.get(index));
		}
		enrichRealtimeCandles(selected);
		return selected;
	}

	private static LocalDateTime realtimeCandleBucket(LocalDateTime eventTime, String timeframe) {
		String normalized = normalizeLiveMonitorTimeframe(timeframe);
		if ("1h".equals(normalized)) {
			return eventTime.withMinute(0).withSecond(0).withNano(0);
		}
		int bucketMinutes = "30m".equals(normalized) ? 30 : ("15m".equals(normalized) ? 15 : ("5m".equals(normalized) ? 5 : 1));
		int minute = (eventTime.getMinute() / bucketMinutes) * bucketMinutes;
		return eventTime.withMinute(minute).withSecond(0).withNano(0);
	}

	private static void enrichRealtimeCandles(List<RealtimeCandle> candles) {
		double cumulativeVolume = 0.0;
		double cumulativeTypicalVolume = 0.0;
		double ema9 = 0.0;
		double ema20 = 0.0;
		List<Double> closes = new ArrayList<Double>();
		for (int index = 0; index < candles.size(); index++) {
			RealtimeCandle candle = candles.get(index);
			double volume = candle.volume > 0.0 ? candle.volume : 1.0;
			double typical = (candle.high + candle.low + candle.close) / 3.0;
			cumulativeVolume += volume;
			cumulativeTypicalVolume += typical * volume;
			candle.vwap = cumulativeVolume > 0.0 ? cumulativeTypicalVolume / cumulativeVolume : candle.close;
			ema9 = nextEma(ema9, candle.close, 9);
			ema20 = nextEma(ema20, candle.close, 20);
			candle.ema9 = ema9;
			candle.ema20 = ema20;
			closes.add(candle.close);
			candle.rsi14 = realtimeRsi(closes, 14);
		}
	}

	private static double nextEma(double previous, double close, int period) {
		if (close <= 0.0) return previous;
		if (previous <= 0.0) return close;
		double multiplier = 2.0 / (period + 1.0);
		return close * multiplier + previous * (1.0 - multiplier);
	}

	private static double realtimeRsi(List<Double> closes, int period) {
		if (closes.size() <= period) {
			return 0.0;
		}
		double gains = 0.0;
		double losses = 0.0;
		for (int index = closes.size() - period; index < closes.size(); index++) {
			double change = closes.get(index) - closes.get(index - 1);
			if (change >= 0.0) {
				gains += change;
			} else {
				losses += Math.abs(change);
			}
		}
		if (losses == 0.0) return 100.0;
		double rs = gains / losses;
		return 100.0 - (100.0 / (1.0 + rs));
	}

	private static LocalDateTime parseDisplayLocalDateTime(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return LocalDateTime.parse(value.trim(), DISPLAY_TIME_FORMAT);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static long secondsSinceDisplayTime(String value) {
		LocalDateTime parsed = parseDisplayLocalDateTime(value);
		if (parsed == null) {
			return -1L;
		}
		LocalDateTime now = ZonedDateTime.now(NEW_YORK_ZONE).toLocalDateTime();
		return Math.max(0L, Duration.between(parsed, now).getSeconds());
	}

	private static LiveSignalAnalysis liveSignalSummary(String symbol) {
		if (liveMarketFeedActive()) {
			List<Bar> oneMinuteBars = realtimeBarsForSymbol(symbol, "1m", 300);
			if (oneMinuteBars.size() >= 20) {
				LocalDate startDate = oneMinuteBars.get(0).marketDate;
				LocalDate endDate = oneMinuteBars.get(oneMinuteBars.size() - 1).marketDate;
				return buildLiveSignalAnalysis(symbol, oneMinuteBars, startDate, endDate);
			}
		}
		LiveSignalAnalysis analysis = new LiveSignalAnalysis();
		FuturesStrategySettings settings = loadFuturesStrategySettings(symbol, STRATEGY_SLOT_LIVE);
		analysis.enabledCount = enabledStrategyCount(settings);
		analysis.strategiesJson = strategyWatchJson(settings, new ArrayList<Signal>(), new ArrayList<Bar>());
		analysis.latestSignalsJson = "[]";
		return analysis;
	}

	private static LiveSignalAnalysis liveSignalSummaryForMonitor(String symbol) {
		LiveSignalAnalysis analysis = new LiveSignalAnalysis();
		FuturesStrategySettings settings = loadFuturesStrategySettings(symbol, STRATEGY_SLOT_LIVE);
		analysis.enabledCount = enabledStrategyCount(settings);
		analysis.strategiesJson = strategyWatchJson(settings, new ArrayList<Signal>(), new ArrayList<Bar>());
		analysis.latestSignalsJson = "[]";
		return analysis;
	}

	private static void appendMonitorPoint(
		StringBuilder json,
		String time,
		double open,
		double high,
		double low,
		double close,
		double volume,
		double vwap,
		double ema9,
		double ema20,
		double rsi14,
		int events,
		int pollEvents,
		boolean live,
		String eventType
	) {
		json.append("{")
			.append("\"time\":").append(jsonString(time)).append(",")
			.append("\"open\":").append(round(open)).append(",")
			.append("\"high\":").append(round(high)).append(",")
			.append("\"low\":").append(round(low)).append(",")
			.append("\"close\":").append(round(close)).append(",")
			.append("\"volume\":").append(round(volume)).append(",")
			.append("\"vwap\":").append(round(vwap)).append(",")
			.append("\"ema9\":").append(round(ema9)).append(",")
			.append("\"ema20\":").append(round(ema20)).append(",")
			.append("\"rsi14\":").append(round(rsi14)).append(",")
			.append("\"live\":").append(live).append(",")
			.append("\"events\":").append(events).append(",")
			.append("\"pollEvents\":").append(pollEvents).append(",")
			.append("\"pollSnapshot\":").append(pollEvents > 0).append(",")
			.append("\"eventType\":").append(jsonString(eventType))
			.append("}");
	}

	private static List<Bar> latestSessionBars(List<Bar> bars, int limit) {
		List<Bar> selected = new ArrayList<Bar>();
		if (bars == null || bars.isEmpty() || limit <= 0) {
			return selected;
		}
		Map<LocalDate, List<Bar>> byDay = groupByDay(bars);
		List<LocalDate> days = new ArrayList<LocalDate>(byDay.keySet());
		Collections.sort(days);
		List<Bar> dayBars = days.isEmpty() ? bars : byDay.get(days.get(days.size() - 1));
		if (dayBars == null || dayBars.isEmpty()) {
			dayBars = bars;
		}
		int start = Math.max(0, dayBars.size() - limit);
		for (int index = start; index < dayBars.size(); index++) {
			selected.add(dayBars.get(index));
		}
		return selected;
	}

	private static String normalizeLiveMonitorTimeframe(String timeframe) {
		String normalized = cleanOrDefault(timeframe, "1m").toLowerCase();
		if ("1".equals(normalized) || "1min".equals(normalized) || "1m".equals(normalized)) {
			return "1m";
		}
		if ("5".equals(normalized) || "5min".equals(normalized) || "5m".equals(normalized)) {
			return "5m";
		}
		if ("15".equals(normalized) || "15min".equals(normalized) || "15m".equals(normalized)) {
			return "15m";
		}
		if ("30".equals(normalized) || "30min".equals(normalized) || "30m".equals(normalized)) {
			return "30m";
		}
		if ("60".equals(normalized) || "60min".equals(normalized) || "1hour".equals(normalized) || "1h".equals(normalized)) {
			return "1h";
		}
		return "1m";
	}

	private static String liveMonitorTimeframeFolder(String timeframe) {
		String normalized = normalizeLiveMonitorTimeframe(timeframe);
		if ("5m".equals(normalized)) {
			return "5min";
		}
		if ("15m".equals(normalized)) {
			return "15min";
		}
		if ("30m".equals(normalized)) {
			return "30min";
		}
		if ("1h".equals(normalized)) {
			return "1hour";
		}
		return TIMEFRAME_FOLDER;
	}

	private static String liveMonitorTimeframeLabel(String timeframe) {
		String normalized = normalizeLiveMonitorTimeframe(timeframe);
		if ("5m".equals(normalized)) {
			return "5 minute";
		}
		if ("15m".equals(normalized)) {
			return "15 minute";
		}
		if ("30m".equals(normalized)) {
			return "30 minute";
		}
		if ("1h".equals(normalized)) {
			return "1 hour";
		}
		return "1 minute";
	}

	private static int liveMonitorDefaultLimit(String timeframe) {
		String normalized = normalizeLiveMonitorTimeframe(timeframe);
		if ("1h".equals(normalized)) {
			return 160;
		}
		if ("30m".equals(normalized)) {
			return 220;
		}
		if ("15m".equals(normalized)) {
			return 240;
		}
		if ("5m".equals(normalized)) {
			return 360;
		}
		return 240;
	}

	private static int liveMonitorTimeframeMinutes(String timeframe) {
		String normalized = normalizeLiveMonitorTimeframe(timeframe);
		if ("1h".equals(normalized)) {
			return 60;
		}
		if ("30m".equals(normalized)) {
			return 30;
		}
		if ("15m".equals(normalized)) {
			return 15;
		}
		if ("5m".equals(normalized)) {
			return 5;
		}
		return 1;
	}

	private static List<RealtimePricePoint> realtimePricePointsForSymbol(String symbol, int limit) {
		ProjectXRealtimeManager.initializeStore();
		List<RealtimePricePoint> points = new ArrayList<RealtimePricePoint>();
		String startedAt = ProjectXRealtimeManager.currentStartedAt();
		String requestedSymbol = normalizeSymbol(symbol);
		String sql = "SELECT realtimeEventID, eventType, contractId, symbol, payloadJson, receivedAt FROM FuturesLiveRealtimeEvents "
			+ "WHERE hub = 'market' AND symbol = ? "
			+ (startedAt == null || startedAt.trim().isEmpty() ? "AND 1 = 0 " : "AND receivedAt >= ? ")
			+ "ORDER BY realtimeEventID DESC LIMIT ?";
		try (Connection conn = ProjectXRealtimeManager.openRealtimeConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, requestedSymbol);
			int parameterIndex = 2;
			if (startedAt != null && !startedAt.trim().isEmpty()) {
				pstmt.setString(parameterIndex++, startedAt);
			}
			pstmt.setInt(parameterIndex, boundedInt(limit, 180, 1, 10000));
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					String payloadJson = rs.getString("payloadJson");
					String eventSymbol = realtimeSymbolFromEvent(
						cleanOrDefault(rs.getString("symbol"), ""),
						cleanOrDefault(rs.getString("contractId"), ""),
						payloadJson
					);
					if (!requestedSymbol.equals(eventSymbol)) {
						continue;
					}
					double price = realtimePriceFromPayload(payloadJson);
					if (price <= 0.0) {
						continue;
					}
					RealtimePricePoint point = new RealtimePricePoint();
					point.id = rs.getInt("realtimeEventID");
					point.time = cleanOrDefault(rs.getString("receivedAt"), "");
					point.eventType = cleanOrDefault(rs.getString("eventType"), "GatewayTrade");
					point.price = price;
					point.volume = realtimeVolumeFromPayload(payloadJson);
					point.pollSnapshot = "LIVE_POLL_SNAPSHOT".equalsIgnoreCase(point.eventType);
					points.add(0, point);
				}
			}
			normalizeRealtimePointVolumes(points);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return points;
	}

	private static void normalizeRealtimePointVolumes(List<RealtimePricePoint> points) {
		if (points == null || points.isEmpty()) {
			return;
		}
		double previousQuoteVolume = -1.0;
		for (int index = 0; index < points.size(); index++) {
			RealtimePricePoint point = points.get(index);
			if (point == null) {
				continue;
			}
			double rawVolume = Math.max(0.0, point.volume);
			if (point.pollSnapshot) {
				point.volume = 0.0;
				continue;
			}
			if ("GatewayQuote".equalsIgnoreCase(point.eventType)) {
				if (rawVolume > 0.0 && previousQuoteVolume >= 0.0 && rawVolume >= previousQuoteVolume) {
					point.volume = rawVolume - previousQuoteVolume;
				} else {
					point.volume = 0.0;
				}
				if (rawVolume > 0.0) {
					previousQuoteVolume = rawVolume;
				}
			} else if (rawVolume <= 0.0) {
				point.volume = 1.0;
			}
		}
	}

	private static String realtimeSymbolFromEvent(String symbol, String contractId, String payloadJson) {
		String direct = normalizeRealtimeSymbolName(symbol);
		if (direct.length() > 0) {
			return direct;
		}
		String payloadSymbol = firstNonBlank(
			jsonText(payloadJson, "symbolName", ""),
			jsonText(payloadJson, "symbol", ""),
			jsonText(payloadJson, "contract", ""),
			contractId
		);
		return normalizeRealtimeSymbolName(payloadSymbol);
	}

	private static String normalizeRealtimeSymbolName(String value) {
		String normalized = cleanOrDefault(value, "").toUpperCase().replace("/", "");
		if (normalized.length() == 0) {
			return "";
		}
		if (normalized.startsWith("CON.F.US.")) {
			String[] parts = normalized.split("\\.");
			if (parts.length >= 4) {
				normalized = parts[3];
			}
		} else if (normalized.startsWith("F.US.")) {
			String[] parts = normalized.split("\\.");
			if (parts.length >= 3) {
				normalized = parts[2];
			}
		}
		int dotIndex = normalized.indexOf('.');
		if (dotIndex > 0) {
			normalized = normalized.substring(0, dotIndex);
		}
		if ("EP".equals(normalized)) {
			return "ES";
		}
		if ("ENQ".equals(normalized)) {
			return "NQ";
		}
		if ("GCE".equals(normalized)) {
			return "GC";
		}
		return normalizeSymbol(normalized);
	}

	private static double realtimePriceFromPayload(String payloadJson) {
		double direct = jsonFirstNumber(payloadJson, new String[] {
			"price", "Price", "lastPrice", "LastPrice", "tradePrice", "TradePrice",
			"last", "Last", "close", "Close", "p"
		}, 0.0);
		if (direct > 0.0) {
			return direct;
		}
		double bid = jsonFirstNumber(payloadJson, new String[] {
			"bid", "Bid", "bestBid", "BestBid", "bidPrice", "BidPrice", "bp"
		}, 0.0);
		double ask = jsonFirstNumber(payloadJson, new String[] {
			"ask", "Ask", "bestAsk", "BestAsk", "askPrice", "AskPrice", "ap"
		}, 0.0);
		if (bid > 0.0 && ask > 0.0) {
			return (bid + ask) / 2.0;
		}
		return Math.max(bid, ask);
	}

	private static double realtimeVolumeFromPayload(String payloadJson) {
		return jsonFirstNumber(payloadJson, new String[] {
			"volume", "Volume", "size", "Size", "qty", "Qty", "quantity", "Quantity", "tradeSize", "TradeSize"
		}, 0.0);
	}

	private static LiveSignalAnalysis buildLiveSignalAnalysis(String symbol, List<Bar> oneMinuteBars, LocalDate startDate, LocalDate endDate) {
		LiveSignalAnalysis analysis = new LiveSignalAnalysis();
		FuturesStrategySettings settings = loadFuturesStrategySettings(symbol, STRATEGY_SLOT_LIVE);
		analysis.enabledCount = enabledStrategyCount(settings);
		List<Signal> signals = new ArrayList<Signal>();
		List<Bar> dayBars = new ArrayList<Bar>();
		try {
			Map<LocalDate, List<Bar>> byDay = groupByDay(oneMinuteBars == null ? new ArrayList<Bar>() : oneMinuteBars);
			List<LocalDate> days = new ArrayList<LocalDate>(byDay.keySet());
			Collections.sort(days);
			if (!days.isEmpty()) {
				int dayIndex = days.size() - 1;
				LocalDate latestDay = days.get(dayIndex);
				dayBars = byDay.get(latestDay);
				List<Bar> fifteenMinuteBars = realtimeBarsForSymbol(symbol, "15m", 96);
				List<Bar> oneHourBars = realtimeBarsForSymbol(symbol, "1h", 48);
				if (fifteenMinuteBars.isEmpty()) {
					fifteenMinuteBars = loadNativeFuturesBars(symbol, startDate, endDate, "15min").bars;
				}
				if (oneHourBars.isEmpty()) {
					oneHourBars = loadNativeFuturesBars(symbol, startDate, endDate, "1hour").bars;
				}
				BacktestConfig config = new BacktestConfig();
				config.symbol = normalizeSymbol(symbol);
				config.strategySettings = settings;
				if (dayBars != null && dayBars.size() >= 40) {
					signals = buildSignals(
						instrumentFor(symbol),
						dayBars,
						previousDayBars(byDay, days, dayIndex),
						groupByDay(fifteenMinuteBars).get(latestDay),
						groupByDay(oneHourBars).get(latestDay),
						config
					);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		Signal latest = latestSignal(signals);
		if (latest != null) {
			analysis.lastSignalCode = cleanOrDefault(latest.strategyCode, "");
			analysis.lastSignalName = cleanOrDefault(latest.strategyName, "");
			analysis.lastSignalSide = cleanOrDefault(latest.side, "");
			analysis.lastSignalTime = signalTime(latest, dayBars);
		}
		analysis.signalCount = signals.size();
		analysis.strategiesJson = strategyWatchJson(settings, signals, dayBars);
		analysis.latestSignalsJson = latestSignalsJson(signals, dayBars, 8);
		return analysis;
	}

	private static int enabledStrategyCount(FuturesStrategySettings settings) {
		FuturesStrategySettings safe = settings == null ? defaultFuturesStrategySettings() : settings;
		int count = 0;
		count += safe.orb.enabled ? 1 : 0;
		count += safe.openingMomentum.enabled ? 1 : 0;
		count += safe.sweep.enabled ? 1 : 0;
		count += safe.vwapPullback.enabled ? 1 : 0;
		count += safe.vwapMeanReversion.enabled ? 1 : 0;
		count += safe.fvg.enabled ? 1 : 0;
		count += safe.closeMomentum.enabled ? 1 : 0;
		count += safe.afternoonContinuation.enabled ? 1 : 0;
		count += safe.marketIntradayMomentum.enabled ? 1 : 0;
		count += safe.keltnerScalp.enabled ? 1 : 0;
		count += safe.keltnerReversion.enabled ? 1 : 0;
		count += safe.microScalp.enabled ? 1 : 0;
		return count;
	}

	private static String strategyWatchJson(FuturesStrategySettings settings, List<Signal> signals, List<Bar> bars) {
		FuturesStrategySettings safe = settings == null ? defaultFuturesStrategySettings() : settings;
		StringBuilder json = new StringBuilder("[");
		appendStrategyWatch(json, 0, "ORB", "Opening Range", safe.orb, signals, bars);
		appendStrategyWatch(json, 1, "OMOM", "Opening Momentum", safe.openingMomentum, signals, bars);
		appendStrategyWatch(json, 2, "SWEEP", "Liquidity Sweep", safe.sweep, signals, bars);
		appendStrategyWatch(json, 3, "VWAP", "VWAP Pullback", safe.vwapPullback, signals, bars);
		appendStrategyWatch(json, 4, "MRVWAP", "VWAP Reversion", safe.vwapMeanReversion, signals, bars);
		appendStrategyWatch(json, 5, "FVG", "Fair Value Gap", safe.fvg, signals, bars);
		appendStrategyWatch(json, 6, "CMOM", "Close Momentum", safe.closeMomentum, signals, bars);
		appendStrategyWatch(json, 7, "AFT", "Afternoon Continuation", safe.afternoonContinuation, signals, bars);
		appendStrategyWatch(json, 8, "MIM", "Market Momentum", safe.marketIntradayMomentum, signals, bars);
		appendStrategyWatch(json, 9, "KELT", "Keltner Scalp", safe.keltnerScalp, signals, bars);
		appendStrategyWatch(json, 10, "KREV", "Keltner Reversion", safe.keltnerReversion, signals, bars);
		appendStrategyWatch(json, 11, "MSCALP", "Micro Scalp", safe.microScalp, signals, bars);
		json.append("]");
		return json.toString();
	}

	private static void appendStrategyWatch(StringBuilder json, int index, String code, String name, StrategyToggle toggle, List<Signal> signals, List<Bar> bars) {
		StrategyToggle safeToggle = toggle == null ? new StrategyToggle(false, 0) : toggle;
		Signal latest = latestSignalForCode(signals, code);
		if (index > 0) {
			json.append(",");
		}
		json.append("{")
			.append("\"code\":").append(jsonString(code)).append(",")
			.append("\"name\":").append(jsonString(name)).append(",")
			.append("\"enabled\":").append(safeToggle.enabled).append(",")
			.append("\"maxTradesPerDay\":").append(safeToggle.maxTradesPerDay).append(",")
			.append("\"status\":").append(jsonString(!safeToggle.enabled ? "Disabled" : (latest == null ? "Watching" : "Signal"))).append(",")
			.append("\"lastSide\":").append(jsonString(latest == null ? "" : latest.side)).append(",")
			.append("\"lastTime\":").append(jsonString(latest == null ? "" : signalTime(latest, bars))).append(",")
			.append("\"lastPrice\":").append(latest == null ? 0.0 : round(latest.entryPrice))
			.append("}");
	}

	private static String latestSignalsJson(List<Signal> signals, List<Bar> bars, int limit) {
		List<Signal> sorted = new ArrayList<Signal>(signals == null ? new ArrayList<Signal>() : signals);
		Collections.sort(sorted, new Comparator<Signal>() {
			@Override
			public int compare(Signal first, Signal second) {
				return Integer.compare(second.entryIndex, first.entryIndex);
			}
		});
		StringBuilder json = new StringBuilder("[");
		int count = 0;
		for (int index = 0; index < sorted.size() && count < limit; index++) {
			Signal signal = sorted.get(index);
			if (count > 0) {
				json.append(",");
			}
			json.append("{")
				.append("\"strategyCode\":").append(jsonString(signal.strategyCode)).append(",")
				.append("\"strategyName\":").append(jsonString(signal.strategyName)).append(",")
				.append("\"side\":").append(jsonString(signal.side)).append(",")
				.append("\"time\":").append(jsonString(signalTime(signal, bars))).append(",")
				.append("\"entryPrice\":").append(round(signal.entryPrice)).append(",")
				.append("\"stopPrice\":").append(round(signal.stopPrice)).append(",")
				.append("\"targetPrice\":").append(round(signal.targetPrice))
				.append("}");
			count++;
		}
		json.append("]");
		return json.toString();
	}

	private static Signal latestSignal(List<Signal> signals) {
		Signal latest = null;
		if (signals == null) {
			return null;
		}
		for (int index = 0; index < signals.size(); index++) {
			Signal signal = signals.get(index);
			if (latest == null || signal.entryIndex > latest.entryIndex) {
				latest = signal;
			}
		}
		return latest;
	}

	private static Signal latestSignalForCode(List<Signal> signals, String code) {
		Signal latest = null;
		if (signals == null) {
			return null;
		}
		for (int index = 0; index < signals.size(); index++) {
			Signal signal = signals.get(index);
			if (!strategyMatchesWatchCode(cleanOrDefault(signal.strategyCode, ""), code)) {
				continue;
			}
			if (latest == null || signal.entryIndex > latest.entryIndex) {
				latest = signal;
			}
		}
		return latest;
	}

	private static boolean strategyMatchesWatchCode(String signalCode, String watchCode) {
		if (signalCode.equals(watchCode)) {
			return true;
		}
		if ("ORB".equals(watchCode) && "ORB2".equals(signalCode)) {
			return true;
		}
		if ("SWEEP".equals(watchCode) && "SWEEP2".equals(signalCode)) {
			return true;
		}
		if ("MIM".equals(watchCode) && "IPB".equals(signalCode)) {
			return true;
		}
		return false;
	}

	private static String signalTime(Signal signal, List<Bar> bars) {
		if (signal == null || bars == null || signal.entryIndex < 0 || signal.entryIndex >= bars.size()) {
			return "";
		}
		return cleanOrDefault(bars.get(signal.entryIndex).displayTime, "");
	}

	public static String getLiveOrderLedgerJson(int limit) {
		initializeStore();
		int safeLimit = boundedInt(limit, 50, 1, 500);
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT * FROM FuturesLiveOrderLedger ORDER BY liveOrderID DESC LIMIT ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, safeLimit);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					if (json.length() > 1) {
						json.append(",");
					}
					json.append("{")
						.append("\"id\":").append(rs.getInt("liveOrderID")).append(",")
						.append("\"snapshotId\":").append(rs.getInt("snapshotID")).append(",")
						.append("\"accountId\":").append(jsonString(rs.getString("accountId"))).append(",")
						.append("\"symbol\":").append(jsonString(rs.getString("symbol"))).append(",")
						.append("\"side\":").append(jsonString(rs.getString("side"))).append(",")
						.append("\"orderType\":").append(jsonString(rs.getString("orderType"))).append(",")
						.append("\"contracts\":").append(rs.getInt("contracts")).append(",")
						.append("\"entryPrice\":").append(round(rs.getDouble("entryPrice"))).append(",")
						.append("\"stopPrice\":").append(round(rs.getDouble("stopPrice"))).append(",")
						.append("\"targetPrice\":").append(round(rs.getDouble("targetPrice"))).append(",")
						.append("\"status\":").append(jsonString(rs.getString("status"))).append(",")
						.append("\"createdAt\":").append(jsonString(rs.getString("createdAt"))).append(",")
						.append("\"updatedAt\":").append(jsonString(rs.getString("updatedAt")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	public static String getLiveRiskEventsJson(int sessionId, int limit) {
		initializeStore();
		int safeLimit = boundedInt(limit, 50, 1, 500);
		StringBuilder json = new StringBuilder("[");
		String sql = sessionId > 0
			? "SELECT * FROM FuturesLiveRiskEvents WHERE sessionID = ? ORDER BY riskEventID DESC LIMIT ?"
			: "SELECT * FROM FuturesLiveRiskEvents ORDER BY riskEventID DESC LIMIT ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			if (sessionId > 0) {
				pstmt.setInt(1, sessionId);
				pstmt.setInt(2, safeLimit);
			} else {
				pstmt.setInt(1, safeLimit);
			}
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					if (json.length() > 1) {
						json.append(",");
					}
					json.append("{")
						.append("\"id\":").append(rs.getInt("riskEventID")).append(",")
						.append("\"sessionId\":").append(rs.getInt("sessionID")).append(",")
						.append("\"snapshotId\":").append(rs.getInt("snapshotID")).append(",")
						.append("\"eventType\":").append(jsonString(rs.getString("eventType"))).append(",")
						.append("\"severity\":").append(jsonString(rs.getString("severity"))).append(",")
						.append("\"message\":").append(jsonString(rs.getString("message"))).append(",")
						.append("\"createdAt\":").append(jsonString(rs.getString("createdAt")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	private static PortfolioBacktestConfig livePortfolioConfigFromSnapshot(LiveStrategySnapshotRow snapshot, LocalDate startDate, LocalDate endDate) {
		if (snapshot.sourcePortfolioBacktestId > 0) {
			String sql = "SELECT * FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ? LIMIT 1";
			try (Connection conn = DatabaseManager.getConnection();
				 PreparedStatement pstmt = conn.prepareStatement(sql)) {
				pstmt.setInt(1, snapshot.sourcePortfolioBacktestId);
				try (ResultSet rs = pstmt.executeQuery()) {
					if (rs.next()) {
						PortfolioBacktestConfig config = buildPortfolioBacktestConfig(
							snapshot.symbols,
							startDate.toString(),
							endDate.toString(),
							rs.getDouble("startingBalance"),
							rs.getDouble("maxTrailingDrawdown"),
							rs.getDouble("dailyLossLimit"),
							rs.getDouble("maxRiskPerTrade"),
							rs.getInt("maxContracts"),
							1.24,
							1.0,
							Math.max(1, snapshot.maxOpenPositions),
							Math.max(1, snapshot.maxAggregateContracts),
							snapshot.maxAggregateMiniUnits,
							true,
							0.0,
							snapshot.fundedProfile
						);
						config.strategySlot = STRATEGY_SLOT_LIVE;
						return config;
					}
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		String portfolioSettings = snapshot.portfolioSettingsJson;
		PortfolioBacktestConfig config = buildPortfolioBacktestConfig(
			snapshot.symbols,
			startDate.toString(),
			endDate.toString(),
			jsonNumber(portfolioSettings, "accountSize", 50000.0),
			jsonNumber(portfolioSettings, "maxTrailingDrawdown", 2000.0),
			jsonNumber(portfolioSettings, "dailyLossLimit", 1000.0),
			jsonNumber(portfolioSettings, "maxRiskPerTrade", 400.0),
			(int) jsonNumber(portfolioSettings, "maxContracts", 50.0),
			jsonNumber(portfolioSettings, "commissionPerContract", 1.24),
			jsonNumber(portfolioSettings, "slippageTicks", 1.0),
			Math.max(1, snapshot.maxOpenPositions),
			Math.max(1, snapshot.maxAggregateContracts),
			snapshot.maxAggregateMiniUnits,
			false,
			jsonNumber(portfolioSettings, "profitTarget", 0.0),
			snapshot.fundedProfile
		);
		config.strategySlot = STRATEGY_SLOT_LIVE;
		return config;
	}

	private static int createLiveEngineSession(LiveStrategySnapshotRow snapshot, String executionMode, String dataMode, String status, String lastBarTime, String startedAt) {
		String sql = "INSERT INTO FuturesLiveEngineSessions (snapshotID, sourcePortfolioBacktestID, executionMode, status, symbols, dataMode, startedAt, lastUpdatedAt, lastBarTime, message) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			pstmt.setInt(1, snapshot.snapshotId);
			if (snapshot.sourcePortfolioBacktestId > 0) {
				pstmt.setInt(2, snapshot.sourcePortfolioBacktestId);
			} else {
				pstmt.setNull(2, Types.INTEGER);
			}
			pstmt.setString(3, cleanOrDefault(executionMode, "DRY_RUN"));
			pstmt.setString(4, cleanOrDefault(status, "RUNNING"));
			pstmt.setString(5, cleanOrDefault(snapshot.symbols, ""));
			pstmt.setString(6, cleanOrDefault(dataMode, "LOCAL_REPLAY"));
			pstmt.setString(7, startedAt);
			pstmt.setString(8, startedAt);
			pstmt.setString(9, cleanOrDefault(lastBarTime, ""));
			pstmt.setString(10, "Live dry-run engine session started.");
			pstmt.executeUpdate();
			try (ResultSet keys = pstmt.getGeneratedKeys()) {
				return keys.next() ? keys.getInt(1) : -1;
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -1;
		}
	}

	private static void updateLiveEngineSession(int sessionId, String status, String lastBarTime, int decisionCount, int acceptedCount, int rejectedCount, String message) {
		String sql = "UPDATE FuturesLiveEngineSessions SET status = ?, lastUpdatedAt = ?, lastBarTime = ?, decisionCount = ?, acceptedDecisionCount = ?, rejectedDecisionCount = ?, message = ? WHERE sessionID = ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, cleanOrDefault(status, "COMPLETED"));
			pstmt.setString(2, LocalDateTime.now().format(DISPLAY_TIME_FORMAT));
			pstmt.setString(3, cleanOrDefault(lastBarTime, ""));
			pstmt.setInt(4, decisionCount);
			pstmt.setInt(5, acceptedCount);
			pstmt.setInt(6, rejectedCount);
			pstmt.setString(7, cleanOrDefault(message, ""));
			pstmt.setInt(8, sessionId);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static void insertLiveSignalDecision(int sessionId, int snapshotId, SignalEvent event, PortfolioPosition position, String status, String reason) {
		int contracts = position == null ? 0 : position.contracts;
		double entryPrice = position == null ? 0.0 : position.entryPrice;
		double stopPrice = position == null ? event.signal.stopPrice : position.stopPrice;
		double targetPrice = position == null ? event.signal.targetPrice : position.targetPrice;
		double miniUnits = position == null ? 0.0 : round(fundedMiniUnitsPerContract(position.symbol) * position.contracts);
		insertLiveDecision(
			sessionId,
			snapshotId,
			event.symbol,
			event.signal.strategyCode,
			event.signal.strategyName,
			event.signal.side,
			event.day + " " + event.entryTime,
			event.day + " " + event.entryTime,
			contracts,
			entryPrice,
			stopPrice,
			targetPrice,
			miniUnits,
			status,
			reason,
			decisionPayloadJson(event, position, reason)
		);
	}

	private static void insertLiveExitDecision(int sessionId, int snapshotId, FuturesTrade trade, String status, String reason) {
		insertLiveDecision(
			sessionId,
			snapshotId,
			trade.symbol,
			trade.strategyCode,
			trade.strategyName,
			trade.side,
			trade.openedAt,
			trade.closedAt,
			trade.contracts,
			trade.exitPrice,
			trade.stopPrice,
			trade.targetPrice,
			round(fundedMiniUnitsPerContract(trade.symbol) * trade.contracts),
			status,
			reason,
				"{"
					+ "\"openedAt\":" + jsonString(trade.openedAt) + ","
					+ "\"closedAt\":" + jsonString(trade.closedAt) + ","
					+ "\"entryPrice\":" + round(trade.entryPrice) + ","
					+ "\"exitPrice\":" + round(trade.exitPrice) + ","
					+ "\"pnl\":" + round(trade.pnl) + ","
					+ "\"mfe\":" + round(trade.mfe) + ","
					+ "\"mae\":" + round(trade.mae) + ","
				+ "\"exitReason\":" + jsonString(trade.exitReason)
				+ "}"
		);
	}

	private static void insertLiveDecision(
		int sessionId,
		int snapshotId,
		String symbol,
		String strategyCode,
		String strategyName,
		String side,
		String signalTime,
		String entryTime,
		int contracts,
		double entryPrice,
		double stopPrice,
		double targetPrice,
		double fundedMiniUnits,
		String status,
		String reason,
		String payloadJson
	) {
		String sql = "INSERT INTO FuturesLiveSignalDecisions (sessionID, snapshotID, symbol, strategyCode, strategyName, side, signalTime, entryTime, contracts, entryPrice, stopPrice, targetPrice, fundedMiniUnits, status, reason, payloadJson, createdAt) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, sessionId);
			pstmt.setInt(2, snapshotId);
			pstmt.setString(3, cleanOrDefault(symbol, ""));
			pstmt.setString(4, cleanOrDefault(strategyCode, ""));
			pstmt.setString(5, cleanOrDefault(strategyName, ""));
			pstmt.setString(6, cleanOrDefault(side, ""));
			pstmt.setString(7, cleanOrDefault(signalTime, ""));
			pstmt.setString(8, cleanOrDefault(entryTime, ""));
			pstmt.setInt(9, contracts);
			pstmt.setDouble(10, entryPrice);
			pstmt.setDouble(11, stopPrice);
			pstmt.setDouble(12, targetPrice);
			pstmt.setDouble(13, fundedMiniUnits);
			pstmt.setString(14, cleanOrDefault(status, ""));
			pstmt.setString(15, cleanOrDefault(reason, ""));
			pstmt.setString(16, jsonObjectOrDefault(payloadJson, "{}"));
			pstmt.setString(17, LocalDateTime.now().format(DISPLAY_TIME_FORMAT));
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static void insertLiveRiskEvent(int sessionId, int snapshotId, String eventType, String severity, String message, String payloadJson) {
		String sql = "INSERT INTO FuturesLiveRiskEvents (sessionID, snapshotID, eventType, severity, message, payloadJson, createdAt) VALUES (?, ?, ?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, sessionId);
			pstmt.setInt(2, snapshotId);
			pstmt.setString(3, cleanOrDefault(eventType, "RISK"));
			pstmt.setString(4, cleanOrDefault(severity, "INFO"));
			pstmt.setString(5, cleanOrDefault(message, ""));
			pstmt.setString(6, jsonObjectOrDefault(payloadJson, "{}"));
			pstmt.setString(7, LocalDateTime.now().format(DISPLAY_TIME_FORMAT));
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static String decisionPayloadJson(SignalEvent event, PortfolioPosition position, String reason) {
		Signal signal = event.signal;
		return "{"
			+ "\"symbol\":" + jsonString(event.symbol) + ","
			+ "\"strategyCode\":" + jsonString(signal.strategyCode) + ","
			+ "\"strategyName\":" + jsonString(signal.strategyName) + ","
			+ "\"side\":" + jsonString(signal.side) + ","
			+ "\"entryTime\":" + jsonString(event.day + " " + event.entryTime) + ","
			+ "\"signalEntryPrice\":" + round(signal.entryPrice) + ","
			+ "\"signalStopPrice\":" + round(signal.stopPrice) + ","
			+ "\"signalTargetPrice\":" + round(signal.targetPrice) + ","
			+ "\"contracts\":" + (position == null ? 0 : position.contracts) + ","
			+ "\"entryPrice\":" + (position == null ? 0.0 : round(position.entryPrice)) + ","
			+ "\"stopPrice\":" + (position == null ? round(signal.stopPrice) : round(position.stopPrice)) + ","
			+ "\"targetPrice\":" + (position == null ? round(signal.targetPrice) : round(position.targetPrice)) + ","
			+ "\"fundedMiniUnits\":" + (position == null ? 0.0 : round(fundedMiniUnitsPerContract(position.symbol) * position.contracts)) + ","
			+ "\"reason\":" + jsonString(reason)
			+ "}";
	}

	private static LiveStrategySnapshotRow loadActiveLiveStrategySnapshot() {
		initializeStore();
		String sql = "SELECT * FROM FuturesLiveStrategySnapshots WHERE active = 1 ORDER BY snapshotID DESC LIMIT 1";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			if (rs.next()) {
				return liveStrategySnapshotRow(rs);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return null;
	}

	private static LiveStrategySnapshotRow liveStrategySnapshotRow(ResultSet rs) throws SQLException {
		LiveStrategySnapshotRow row = new LiveStrategySnapshotRow();
		row.snapshotId = rs.getInt("snapshotID");
		row.sourcePortfolioBacktestId = rs.getInt("sourcePortfolioBacktestID");
		if (rs.wasNull()) {
			row.sourcePortfolioBacktestId = 0;
		}
		row.symbols = cleanSymbolsCsv(rs.getString("symbols"));
		row.fundedProfile = cleanOrDefault(rs.getString("fundedProfile"), "CUSTOM");
		row.accountMode = cleanOrDefault(rs.getString("accountMode"), TOPSTEPX_PRACTICE_ACCOUNT_MODE);
		row.practiceAccountId = cleanOrDefault(rs.getString("practiceAccountId"), TOPSTEPX_PRACTICE_ACCOUNT_ID);
		row.maxOpenPositions = rs.getInt("maxOpenPositions");
		row.maxAggregateContracts = rs.getInt("maxAggregateContracts");
		row.maxAggregateMiniUnits = rs.getDouble("maxAggregateMiniUnits");
		row.strategySettingsJson = rs.getString("strategySettingsJson");
		row.riskSettingsJson = rs.getString("riskSettingsJson");
		row.portfolioSettingsJson = rs.getString("portfolioSettingsJson");
		row.sourceMetricsJson = rs.getString("sourceMetricsJson");
		row.codeVersion = cleanOrDefault(rs.getString("codeVersion"), "local-worktree");
		row.createdAt = cleanOrDefault(rs.getString("createdAt"), "");
		row.updatedAt = cleanOrDefault(rs.getString("updatedAt"), "");
		return row;
	}

	private static String liveStrategySnapshotJson(LiveStrategySnapshotRow snapshot) {
		if (snapshot == null) {
			return "null";
		}
		String label = snapshot.sourcePortfolioBacktestId > 0
			? "Portfolio Backtest #" + snapshot.sourcePortfolioBacktestId
			: "Live Strategy Slot";
		return "{"
			+ "\"snapshotId\":" + snapshot.snapshotId + ","
			+ "\"sourcePortfolioBacktestId\":" + snapshot.sourcePortfolioBacktestId + ","
			+ "\"label\":" + jsonString(label) + ","
			+ "\"symbols\":" + jsonString(snapshot.symbols) + ","
			+ "\"symbolList\":" + jsonStringArray(parseSymbols(snapshot.symbols)) + ","
			+ "\"fundedProfile\":" + jsonString(snapshot.fundedProfile) + ","
			+ "\"accountMode\":" + jsonString(snapshot.accountMode) + ","
			+ "\"practiceAccountId\":" + jsonString(snapshot.practiceAccountId) + ","
			+ "\"maxOpenPositions\":" + snapshot.maxOpenPositions + ","
			+ "\"maxAggregateContracts\":" + snapshot.maxAggregateContracts + ","
			+ "\"maxAggregateMiniUnits\":" + round(snapshot.maxAggregateMiniUnits) + ","
			+ "\"strategySettings\":" + jsonObjectOrDefault(snapshot.strategySettingsJson, "{}") + ","
			+ "\"riskSettings\":" + jsonObjectOrDefault(snapshot.riskSettingsJson, "{}") + ","
			+ "\"portfolioSettings\":" + jsonObjectOrDefault(snapshot.portfolioSettingsJson, "{}") + ","
			+ "\"sourceMetrics\":" + jsonObjectOrDefault(snapshot.sourceMetricsJson, "{}") + ","
			+ "\"codeVersion\":" + jsonString(snapshot.codeVersion) + ","
			+ "\"createdAt\":" + jsonString(snapshot.createdAt) + ","
			+ "\"updatedAt\":" + jsonString(snapshot.updatedAt)
			+ "}";
	}

	private static int recommendedLiveSnapshotSourceId() {
		int preferredId = parseInt(DEFAULT_LIVE_SNAPSHOT_SOURCE_ID, 242);
		if (portfolioBacktestExists(preferredId)) {
			return preferredId;
		}
		String sql = "SELECT portfolioBacktestID FROM FuturesPortfolioBacktests WHERE ruleViolation = 0 ORDER BY portfolioBacktestID DESC LIMIT 1";
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			if (rs.next()) {
				return rs.getInt(1);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return 0;
	}

	private static boolean portfolioBacktestExists(int portfolioBacktestId) {
		String sql = "SELECT 1 FROM FuturesPortfolioBacktests WHERE portfolioBacktestID = ? LIMIT 1";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, portfolioBacktestId);
			try (ResultSet rs = pstmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	private static String strategySettingsBySymbolJson(String symbols) {
		return strategySettingsBySymbolJson(symbols, STRATEGY_SLOT_BACKTEST);
	}

	private static String strategySettingsBySymbolJson(String symbols, String slot) {
		List<String> symbolList = parseSymbols(symbols);
		StringBuilder json = new StringBuilder("{");
		for (int index = 0; index < symbolList.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			String symbol = symbolList.get(index);
			json.append(jsonString(symbol)).append(":").append(getFuturesStrategySettingsJson(symbol, slot));
		}
		json.append("}");
		return json.toString();
	}

	private static String riskSettingsBySymbolJson(String symbols) {
		List<String> symbolList = parseSymbols(symbols);
		StringBuilder json = new StringBuilder("{");
		for (int index = 0; index < symbolList.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			String symbol = symbolList.get(index);
			json.append(jsonString(symbol)).append(":").append(getFuturesRiskSettingsJson(symbol));
		}
		json.append("}");
		return json.toString();
	}

	private static String livePortfolioSettingsJson(ResultSet rs) throws SQLException {
		return "{"
			+ "\"accountSize\":" + round(rs.getDouble("startingBalance")) + ","
			+ "\"fundedProfile\":" + jsonString(cleanOrDefault(rs.getString("fundedProfile"), "CUSTOM")) + ","
			+ "\"maxTrailingDrawdown\":" + round(rs.getDouble("maxTrailingDrawdown")) + ","
			+ "\"dailyLossLimit\":" + round(rs.getDouble("dailyLossLimit")) + ","
			+ "\"maxRiskPerTrade\":" + round(rs.getDouble("maxRiskPerTrade")) + ","
			+ "\"maxContracts\":" + rs.getInt("maxContracts") + ","
			+ "\"maxOpenPositions\":" + rs.getInt("maxOpenPositions") + ","
			+ "\"maxAggregateContracts\":" + rs.getInt("maxAggregateContracts") + ","
			+ "\"maxAggregateMiniUnits\":" + round(rs.getDouble("maxAggregateMiniUnits")) + ","
			+ "\"maxConcurrentMiniUnits\":" + round(rs.getDouble("maxConcurrentMiniUnits")) + ","
			+ "\"trailingThreshold\":" + round(rs.getDouble("trailingThreshold")) + ","
			+ "\"accountMode\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_MODE) + ","
			+ "\"practiceAccountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + ","
			+ "\"settingsSource\":" + jsonString("Current saved per-symbol strategy/risk settings captured at snapshot time.")
			+ "}";
	}

	private static String livePortfolioSettingsJson(String symbols, FundedRuleProfile profile, String accountId, String accountMode) {
		return "{"
			+ "\"accountSize\":" + round(profile.accountSize) + ","
			+ "\"fundedProfile\":" + jsonString(profile.code) + ","
			+ "\"maxTrailingDrawdown\":" + round(profile.maxTrailingDrawdown) + ","
			+ "\"dailyLossLimit\":" + round(profile.dailyLossLimit) + ","
			+ "\"maxRiskPerTrade\":" + round(profile.maxRiskPerTrade) + ","
			+ "\"maxContracts\":" + profile.maxContracts + ","
			+ "\"maxMicroContracts\":" + profile.maxMicroContracts + ","
			+ "\"maxOpenPositions\":" + profile.maxOpenPositions + ","
			+ "\"maxAggregateContracts\":" + profile.maxAggregateContracts + ","
			+ "\"maxAggregateMiniUnits\":" + round(profile.maxAggregateMiniUnits) + ","
			+ "\"profitTarget\":" + round(profile.profitTarget) + ","
			+ "\"commissionPerContract\":1.24,"
			+ "\"slippageTicks\":1.0,"
			+ "\"symbols\":" + jsonString(cleanSymbolsCsv(symbols)) + ","
			+ "\"accountMode\":" + jsonString(accountMode) + ","
			+ "\"practiceAccountId\":" + jsonString(accountId) + ","
			+ "\"settingsSource\":" + jsonString("Live Strategy configuration copied from Backtest Strategy.")
			+ "}";
	}

	private static String liveSourceMetricsJson(ResultSet rs) throws SQLException {
		return "{"
			+ "\"sourcePortfolioBacktestId\":" + rs.getInt("portfolioBacktestID") + ","
			+ "\"symbols\":" + jsonString(cleanSymbolsCsv(rs.getString("symbols"))) + ","
			+ "\"startDate\":" + jsonString(rs.getString("startDate")) + ","
			+ "\"endDate\":" + jsonString(rs.getString("endDate")) + ","
			+ "\"totalProfit\":" + round(rs.getDouble("totalProfit")) + ","
			+ "\"numTrades\":" + rs.getInt("numTrades") + ","
			+ "\"winRate\":" + round(rs.getDouble("winRate")) + ","
			+ "\"profitFactor\":" + round(rs.getDouble("profitFactor")) + ","
			+ "\"maxDrawdownPct\":" + round(rs.getDouble("maxDrawdownPct")) + ","
			+ "\"maxIntradayLoss\":" + round(rs.getDouble("maxIntradayLoss")) + ","
			+ "\"maxAggregateMae\":" + round(rs.getDouble("maxAggregateMae")) + ","
			+ "\"ruleViolation\":" + (rs.getInt("ruleViolation") == 1) + ","
			+ "\"ruleMessage\":" + jsonString(rs.getString("ruleMessage")) + ","
			+ "\"createdAt\":" + jsonString(rs.getString("createdAt"))
			+ "}";
	}

	private static String liveSourceMetricsConfigJson(String symbols, FundedRuleProfile profile) {
		return "{"
			+ "\"source\":\"BACKTEST_STRATEGY_CONFIG\","
			+ "\"symbols\":" + jsonString(cleanSymbolsCsv(symbols)) + ","
			+ "\"fundedProfile\":" + jsonString(profile.code) + ","
			+ "\"description\":" + jsonString("Live Strategy was copied from the saved Backtest Strategy configuration.") + ","
			+ "\"createdAt\":" + jsonString(LocalDateTime.now().format(DISPLAY_TIME_FORMAT))
			+ "}";
	}

	private static String cleanSymbolsCsv(String symbols) {
		List<String> symbolList = parseSymbols(symbols);
		StringBuilder value = new StringBuilder();
		for (int index = 0; index < symbolList.size(); index++) {
			if (index > 0) {
				value.append(",");
			}
			value.append(symbolList.get(index));
		}
		return value.toString();
	}

	private static boolean csvContainsSymbol(String symbols, String symbol) {
		String normalized = normalizeSymbol(symbol);
		List<String> symbolList = parseSymbols(symbols);
		return symbolList.contains(normalized);
	}

	private static MarketSessionStatus currentMarketSessionStatus() {
		ZonedDateTime now = ZonedDateTime.now(NEW_YORK_ZONE);
		LocalTime time = now.toLocalTime();
		DayOfWeek day = now.getDayOfWeek();
		boolean weekday = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
		MarketSessionStatus status = new MarketSessionStatus();
		status.tradingDay = weekday;
		status.rthOpen = weekday && !time.isBefore(RTH_START) && time.isBefore(RTH_END);
		status.entryWindowOpen = status.rthOpen && time.isBefore(FORCED_EXIT_TIME);
		status.now = now.toLocalDateTime().format(DISPLAY_TIME_FORMAT);
		if (!weekday) {
			status.code = "WEEKEND_CLOSED";
			status.label = "Market Closed";
			status.detail = "Regular strategy session is closed for the weekend.";
			status.nextAction = "Wait for the next weekday regular session.";
		} else if (time.isBefore(RTH_START)) {
			status.code = "PREMARKET_WAIT";
			status.label = "Pre-Market";
			status.detail = "Live prices may stream, but the strategy waits for the 9:30 AM ET regular session.";
			status.nextAction = "Wait for market open.";
		} else if (time.isBefore(FORCED_EXIT_TIME)) {
			status.code = "RTH_OPEN";
			status.label = "Market Open";
			status.detail = "Strategy entries are enabled while live candles and risk checks pass.";
			status.nextAction = "Analyze live candles.";
		} else if (time.isBefore(RTH_END)) {
			status.code = "FLATTEN_WINDOW";
			status.label = "Flatten Window";
			status.detail = "New entries are blocked near the close; the bot waits for existing bracket orders or flat handling.";
			status.nextAction = "Block new entries.";
		} else {
			status.code = "RTH_CLOSED";
			status.label = "Market Closed";
			status.detail = "Regular strategy session is closed after 4:00 PM ET.";
			status.nextAction = "Wait for the next regular session.";
		}
		return status;
	}

	private static String marketSessionJson(MarketSessionStatus status) {
		MarketSessionStatus safe = status == null ? currentMarketSessionStatus() : status;
		return "{"
			+ "\"code\":" + jsonString(safe.code) + ","
			+ "\"label\":" + jsonString(safe.label) + ","
			+ "\"detail\":" + jsonString(safe.detail) + ","
			+ "\"now\":" + jsonString(safe.now) + ","
			+ "\"tradingDay\":" + safe.tradingDay + ","
			+ "\"rthOpen\":" + safe.rthOpen + ","
			+ "\"entryWindowOpen\":" + safe.entryWindowOpen + ","
			+ "\"nextAction\":" + jsonString(safe.nextAction)
			+ "}";
	}

	private static String normalizeOrderSide(String side) {
		if (side == null) {
			return "";
		}
		String normalized = side.trim().toUpperCase();
		if ("LONG".equals(normalized) || "BUY".equals(normalized)) {
			return "BUY";
		}
		if ("SHORT".equals(normalized) || "SELL".equals(normalized)) {
			return "SELL";
		}
		return "";
	}

	private static int insertLiveOrderLedger(
		int snapshotId,
		String accountId,
		String symbol,
		String side,
		String orderType,
		int contracts,
		double entryPrice,
		double stopPrice,
		double targetPrice,
		String status,
		String requestJson,
		String responseJson,
		String createdAt
	) {
		String sql = "INSERT INTO FuturesLiveOrderLedger (snapshotID, accountId, symbol, side, orderType, contracts, entryPrice, stopPrice, targetPrice, status, requestJson, responseJson, createdAt, updatedAt) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			pstmt.setInt(1, snapshotId);
			pstmt.setString(2, cleanOrDefault(accountId, ""));
			pstmt.setString(3, symbol);
			pstmt.setString(4, side);
			pstmt.setString(5, orderType);
			pstmt.setInt(6, contracts);
			pstmt.setDouble(7, entryPrice);
			pstmt.setDouble(8, stopPrice);
			pstmt.setDouble(9, targetPrice);
			pstmt.setString(10, status);
			pstmt.setString(11, requestJson);
			pstmt.setString(12, responseJson);
			pstmt.setString(13, createdAt);
			pstmt.setString(14, createdAt);
			pstmt.executeUpdate();
			try (ResultSet keys = pstmt.getGeneratedKeys()) {
				return keys.next() ? keys.getInt(1) : -1;
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -1;
		}
	}

	private static void recordLiveAudit(String eventType, String severity, String message, String payloadJson) {
		String sql = "INSERT INTO FuturesLiveAuditLog (eventType, severity, message, payloadJson, createdAt) VALUES (?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, cleanOrDefault(eventType, "EVENT"));
			pstmt.setString(2, cleanOrDefault(severity, "INFO"));
			pstmt.setString(3, cleanOrDefault(message, ""));
			pstmt.setString(4, jsonObjectOrDefault(payloadJson, "{}"));
			pstmt.setString(5, LocalDateTime.now().format(DISPLAY_TIME_FORMAT));
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static synchronized void startLiveAutomationLoop() {
		if (liveAutomationTask != null && !liveAutomationTask.isDone()) {
			return;
		}
		liveAutomationTask = LIVE_AUTOMATION_EXECUTOR.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				try {
					runLiveRealtimeCycle();
				} catch (Exception e) {
					e.printStackTrace();
					boolean brokerMode;
					synchronized (FuturesManager.class) {
						brokerMode = "TOPSTEPX".equals(liveSession.executionMode);
						liveSession.lastUpdatedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
						liveSession.lastDecision = "Live automation cycle failed: " + cleanOrDefault(e.getMessage(), "unknown error");
					}
					recordLiveAudit("LIVE_AUTOMATION_ERROR", "ERROR", "Live automation cycle failed.", "{\"error\":" + jsonString(cleanOrDefault(e.getMessage(), "")) + "}");
					if (brokerMode) {
						disarmPracticeOrders("Practice order submission was guarded after a critical live automation failure.");
					}
				}
			}
		}, 2, 5, TimeUnit.SECONDS);
	}

	private static synchronized void stopLiveAutomationLoop() {
		if (liveAutomationTask != null) {
			liveAutomationTask.cancel(false);
			liveAutomationTask = null;
		}
	}

	private static void runLiveRealtimeCycle() {
		FuturesLiveSession session;
		synchronized (FuturesManager.class) {
			if (!liveSession.running) {
				return;
			}
			session = copyLiveSession(liveSession);
			liveSession.automationCycles++;
		}
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		if (snapshot == null || session.sessionId <= 0) {
			return;
		}
		MarketSessionStatus marketStatus = currentMarketSessionStatus();
		if (!marketStatus.entryWindowOpen) {
			String message = marketStatus.label + ": " + marketStatus.detail;
			if ("TOPSTEPX".equals(session.executionMode)
				&& !session.flattenAttempted
				&& ("FLATTEN_WINDOW".equals(marketStatus.code) || "RTH_CLOSED".equals(marketStatus.code))) {
				String flattenResponse = FuturesConnectionManager.flattenTopstepxPracticeAccount(TOPSTEPX_PRACTICE_ACCOUNT_ID);
				boolean flattened = jsonBoolean(flattenResponse, "success");
				message = marketStatus.label + ": " + marketStatus.detail + " "
					+ (flattened ? "Practice flatten/cancel sweep completed." : "Practice flatten/cancel sweep needs attention: " + jsonStringSummary(flattenResponse));
				recordLiveAudit(flattened ? "PRACTICE_FLATTEN_SWEEP" : "PRACTICE_FLATTEN_SWEEP_FAILED", flattened ? "WARN" : "ERROR", message, flattenResponse);
				synchronized (FuturesManager.class) {
					liveSession.flattenAttempted = true;
					session = copyLiveSession(liveSession);
				}
				if (isOrderSubmissionArmed()) {
					disarmPracticeOrders(flattened
						? "Practice order submission was guarded after the post-close flatten/cancel sweep."
						: "Practice order submission was guarded after a failed post-close flatten/cancel sweep.");
				}
			}
			synchronized (FuturesManager.class) {
				liveSession.lastUpdatedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
				liveSession.lastDecision = message;
			}
			updateLiveEngineSession(session.sessionId, "RUNNING", session.lastBarTime, session.decisionCount, session.acceptedDecisionCount, session.rejectedDecisionCount, message);
			return;
		}

		List<String> symbols = parseSymbols(snapshot.symbols);
		int acceptedThisCycle = 0;
		int rejectedThisCycle = 0;
		String lastProcessed = "";
		for (int index = 0; index < symbols.size(); index++) {
			String symbol = symbols.get(index);
			List<Bar> bars = realtimeBarsForSymbol(symbol, "1m", 240);
			if (bars.size() < 6) {
				continue;
			}
			Bar executionBar = bars.get(bars.size() - 1);
			Bar signalBar = bars.get(bars.size() - 2);
			lastProcessed = executionBar.displayTime;
			if (!executionBar.marketTime.isBefore(FORCED_EXIT_TIME)) {
				continue;
			}

			InstrumentSpec spec = instrumentFor(symbol);
			BacktestConfig config = liveSignalConfigFor(symbol, session, snapshot);
			List<Signal> signals = buildSignals(
				spec,
				bars,
				previousLocalDayBarsForLive(symbol, signalBar.marketDate),
				realtimeBarsForSymbol(symbol, "15m", 96),
				realtimeBarsForSymbol(symbol, "1h", 48),
				config
			);
			for (int signalIndex = 0; signalIndex < signals.size(); signalIndex++) {
				Signal signal = signals.get(signalIndex);
				if (signal.entryIndex != bars.size() - 2) {
					continue;
				}
				String signalTime = signalBar.displayTime;
				if (liveDecisionExists(session.sessionId, symbol, signal.strategyCode, signalTime)) {
					continue;
				}
				LiveSignalOrder order = validateLiveSignalOrder(session, snapshot, config, spec, symbol, signal, executionBar);
				if (!order.accepted) {
					insertLiveSignalDecisionFromSignal(session.sessionId, snapshot.snapshotId, symbol, signal, signalTime, executionBar.displayTime, 0, 0.0, signal.stopPrice, signal.targetPrice, 0.0, "REJECTED", order.reason);
					rejectedThisCycle++;
					continue;
				}
				String status;
				String reason;
				if ("TOPSTEPX".equals(session.executionMode)) {
					String response = submitTopstepxPracticeOrder(symbol, signal.side, order.contracts, order.entryPrice, order.stopPrice, order.targetPrice, signal.strategyCode + " live signal");
					boolean submitted = jsonBoolean(response, "success");
					status = submitted ? "SUBMITTED_TOPSTEPX" : "SUBMIT_BLOCKED";
					reason = submitted ? "TopstepX practice order submitted from live signal." : "TopstepX practice order was blocked: " + jsonStringSummary(response);
					if (!submitted && isOrderSubmissionArmed()) {
						disarmPracticeOrders("Practice order submission was guarded after a TopstepX submission block.");
					}
				} else {
					status = "ACCEPTED_SIMULATED_LIVE";
					reason = "Live signal accepted in simulated mode.";
				}
				insertLiveSignalDecisionFromSignal(session.sessionId, snapshot.snapshotId, symbol, signal, signalTime, executionBar.displayTime, order.contracts, order.entryPrice, order.stopPrice, order.targetPrice, order.fundedMiniUnits, status, reason);
				if (status.startsWith("SUBMIT_BLOCKED")) {
					rejectedThisCycle++;
				} else {
					acceptedThisCycle++;
				}
			}
		}

		if (acceptedThisCycle > 0 || rejectedThisCycle > 0 || lastProcessed.length() > 0) {
			String message = acceptedThisCycle > 0
				? "Live strategy processed and submitted " + acceptedThisCycle + " practice order signal(s)."
				: "Live strategy processed latest candles; no new accepted order signal.";
			synchronized (FuturesManager.class) {
				liveSession.lastUpdatedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
				if (lastProcessed.length() > 0) {
					liveSession.lastBarTime = lastProcessed;
					liveSession.lastProcessedLiveBarTime = lastProcessed;
				}
				liveSession.decisionCount += acceptedThisCycle + rejectedThisCycle;
				liveSession.acceptedDecisionCount += acceptedThisCycle;
				liveSession.rejectedDecisionCount += rejectedThisCycle;
				liveSession.lastDecision = message;
				session = copyLiveSession(liveSession);
			}
			updateLiveEngineSession(session.sessionId, "RUNNING", session.lastBarTime, session.decisionCount, session.acceptedDecisionCount, session.rejectedDecisionCount, message);
		}
	}

	private static class LiveSignalOrder {
		private boolean accepted;
		private String reason;
		private int contracts;
		private double entryPrice;
		private double stopPrice;
		private double targetPrice;
		private double fundedMiniUnits;
	}

	private static LiveSignalOrder validateLiveSignalOrder(
		FuturesLiveSession session,
		LiveStrategySnapshotRow snapshot,
		BacktestConfig config,
		InstrumentSpec spec,
		String symbol,
		Signal signal,
		Bar executionBar
	) {
		LiveSignalOrder order = new LiveSignalOrder();
		order.entryPrice = roundToTick(spec, executionBar.open > 0.0 ? executionBar.open : executionBar.close);
		order.stopPrice = roundToTick(spec, signal.stopPrice);
		order.targetPrice = roundToTick(spec, signal.targetPrice);
		double riskPoints = Math.abs(order.entryPrice - order.stopPrice);
		if (riskPoints <= 0.0) {
			order.reason = "Rejected: signal has no live stop distance.";
			return order;
		}
		FuturesRiskSettings risk = loadFuturesRiskSettings(symbol);
		double maxRisk = Math.min(positiveOrDefault(session.maxRiskPerTrade, config.maxRiskPerTrade), positiveOrDefault(risk.maxRiskPerTrade, config.maxRiskPerTrade));
		int maxContracts = Math.min(Math.max(1, session.maxContracts), Math.max(1, risk.maxContracts));
		maxContracts = Math.min(maxContracts, topstepMaxContractsForSymbol(symbol));
		if (snapshot.maxAggregateMiniUnits > 0.0) {
			maxContracts = Math.min(maxContracts, contractsAllowedByMiniUnitRoom(symbol, snapshot.maxAggregateMiniUnits));
		}
		double riskPerContract = (riskPoints / spec.tickSize * spec.tickValue) + (risk.commissionPerContract * 2.0);
		int contracts = Math.min(maxContracts, (int) Math.floor(maxRisk / Math.max(1.0, riskPerContract)));
		if (contracts < 1) {
			order.reason = "Rejected: live signal failed risk sizing.";
			return order;
		}
		order.contracts = contracts;
		order.fundedMiniUnits = round(fundedMiniUnitsPerContract(symbol) * contracts);
		order.accepted = true;
		order.reason = "Accepted live signal.";
		return order;
	}

	private static BacktestConfig liveSignalConfigFor(String symbol, FuturesLiveSession session, LiveStrategySnapshotRow snapshot) {
		BacktestConfig config = buildBacktestConfig(
			symbol,
			LocalDate.now(NEW_YORK_ZONE).toString(),
			LocalDate.now(NEW_YORK_ZONE).toString(),
			session.accountSize,
			session.maxTrailingDrawdown,
			session.dailyLossLimit,
			session.maxRiskPerTrade,
			session.maxContracts,
			1.24,
			1.0,
			0.0
		);
		config.strategySettings = loadFuturesStrategySettings(symbol, STRATEGY_SLOT_LIVE);
		config.fundedProfile = snapshot.fundedProfile;
		return config;
	}

	private static List<Bar> realtimeBarsForSymbol(String symbol, String timeframe, int limit) {
		LiveWarmupBars warmup = liveWarmupBarsForSymbol(symbol, timeframe, limit);
		List<RealtimePricePoint> points = realtimePricePointsForSymbol(symbol, realtimePointLimitForCandles(limit, timeframe));
		List<RealtimeCandle> candles = aggregateRealtimeCandles(points, timeframe, limit);
		List<Bar> liveBars = new ArrayList<Bar>();
		InstrumentSpec spec = instrumentFor(symbol);
		for (int index = 0; index < candles.size(); index++) {
			RealtimeCandle candle = candles.get(index);
			LocalDateTime eventTime = parseDisplayLocalDateTime(candle.time);
			if (eventTime == null) {
				continue;
			}
			Bar bar = new Bar();
			bar.displayTime = candle.time;
			bar.marketDate = eventTime.toLocalDate();
			bar.marketTime = eventTime.toLocalTime();
			bar.open = candle.open;
			bar.high = candle.high;
			bar.low = candle.low;
			bar.close = candle.close;
			bar.volume = candle.volume;
			bar.vwap = candle.vwap;
			bar.ema9 = candle.ema9;
			bar.ema20 = candle.ema20;
			bar.ema50 = candle.ema20;
			bar.rsi14 = candle.rsi14 <= 0.0 ? 50.0 : candle.rsi14;
			bar.rangeTicks = spec.tickSize <= 0.0 ? 0.0 : Math.abs(bar.high - bar.low) / spec.tickSize;
			double range = Math.abs(bar.high - bar.low);
			bar.bodyPct = range <= 0.0 ? 0.0 : (Math.abs(bar.close - bar.open) / range) * 100.0;
			liveBars.add(bar);
		}
		List<Bar> bars = mergeBarSeries(warmup.bars, liveBars, limit);
		enrichLiveBars(bars, spec);
		return bars;
	}

	private static void applyRealtimeBarDerivedFields(List<Bar> bars, InstrumentSpec spec) {
		if (bars.isEmpty()) {
			return;
		}
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			int volumeStart = Math.max(0, index - 19);
			double volumeSum = 0.0;
			for (int volumeIndex = volumeStart; volumeIndex <= index; volumeIndex++) {
				volumeSum += bars.get(volumeIndex).volume;
			}
			bar.volumeSma20 = volumeSum / (index - volumeStart + 1);
			int atrStart = Math.max(0, index - 13);
			double trSum = 0.0;
			for (int trIndex = atrStart; trIndex <= index; trIndex++) {
				Bar current = bars.get(trIndex);
				double previousClose = trIndex == 0 ? current.close : bars.get(trIndex - 1).close;
				double trueRange = Math.max(current.high - current.low, Math.max(Math.abs(current.high - previousClose), Math.abs(current.low - previousClose)));
				trSum += trueRange;
			}
			bar.atr14 = trSum / (index - atrStart + 1);
			if (bar.rangeTicks <= 0.0 && spec.tickSize > 0.0) {
				bar.rangeTicks = Math.abs(bar.high - bar.low) / spec.tickSize;
			}
		}
	}

	private static List<Bar> previousLocalDayBarsForLive(String symbol, LocalDate currentDay) {
		LocalDate endDate = currentDay == null ? LocalDate.now(NEW_YORK_ZONE) : currentDay;
		DataBundle bundle = loadNativeFuturesBars(symbol, endDate.minusDays(10), endDate, TIMEFRAME_FOLDER);
		Map<LocalDate, List<Bar>> byDay = groupByDay(bundle.bars);
		List<LocalDate> days = new ArrayList<LocalDate>(byDay.keySet());
		Collections.sort(days);
		for (int index = days.size() - 1; index >= 0; index--) {
			LocalDate day = days.get(index);
			if (currentDay == null || day.isBefore(currentDay)) {
				return byDay.get(day);
			}
		}
		return new ArrayList<Bar>();
	}

	private static boolean liveDecisionExists(int sessionId, String symbol, String strategyCode, String signalTime) {
		String sql = "SELECT 1 FROM FuturesLiveSignalDecisions WHERE sessionID = ? AND symbol = ? AND strategyCode = ? AND signalTime = ? LIMIT 1";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, sessionId);
			pstmt.setString(2, normalizeSymbol(symbol));
			pstmt.setString(3, cleanOrDefault(strategyCode, ""));
			pstmt.setString(4, cleanOrDefault(signalTime, ""));
			try (ResultSet rs = pstmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return true;
		}
	}

	private static void insertLiveSignalDecisionFromSignal(
		int sessionId,
		int snapshotId,
		String symbol,
		Signal signal,
		String signalTime,
		String entryTime,
		int contracts,
		double entryPrice,
		double stopPrice,
		double targetPrice,
		double fundedMiniUnits,
		String status,
		String reason
	) {
		insertLiveDecision(
			sessionId,
			snapshotId,
			symbol,
			signal.strategyCode,
			signal.strategyName,
			signal.side,
			signalTime,
			entryTime,
			contracts,
			entryPrice,
			stopPrice,
			targetPrice,
			fundedMiniUnits,
			status,
			reason,
			"{"
				+ "\"symbol\":" + jsonString(symbol) + ","
				+ "\"strategyCode\":" + jsonString(signal.strategyCode) + ","
				+ "\"strategyName\":" + jsonString(signal.strategyName) + ","
				+ "\"side\":" + jsonString(signal.side) + ","
				+ "\"signalTime\":" + jsonString(signalTime) + ","
				+ "\"entryTime\":" + jsonString(entryTime) + ","
				+ "\"signalEntryPrice\":" + round(signal.entryPrice) + ","
				+ "\"entryPrice\":" + round(entryPrice) + ","
				+ "\"stopPrice\":" + round(stopPrice) + ","
				+ "\"targetPrice\":" + round(targetPrice) + ","
				+ "\"contracts\":" + contracts + ","
				+ "\"fundedMiniUnits\":" + round(fundedMiniUnits) + ","
				+ "\"reason\":" + jsonString(reason)
				+ "}"
		);
	}

	private static String jsonStringSummary(String json) {
		String message = jsonText(json, "message", "");
		if (message != null && message.trim().length() > 0) {
			return message;
		}
		return cleanOrDefault(json, "").length() > 180 ? cleanOrDefault(json, "").substring(0, 180) : cleanOrDefault(json, "");
	}

	private static FuturesLiveSession copyLiveSession(FuturesLiveSession source) {
		FuturesLiveSession copy = new FuturesLiveSession();
		copy.running = source.running;
		copy.sessionId = source.sessionId;
		copy.symbol = source.symbol;
		copy.executionMode = source.executionMode;
		copy.fundedProfile = source.fundedProfile;
		copy.symbols = source.symbols;
		copy.startedAt = source.startedAt;
		copy.lastUpdatedAt = source.lastUpdatedAt;
		copy.lastDryRunAt = source.lastDryRunAt;
		copy.dataMode = source.dataMode;
		copy.lastBarTime = source.lastBarTime;
		copy.accountSize = source.accountSize;
		copy.maxTrailingDrawdown = source.maxTrailingDrawdown;
		copy.dailyLossLimit = source.dailyLossLimit;
		copy.maxRiskPerTrade = source.maxRiskPerTrade;
		copy.maxContracts = source.maxContracts;
		copy.maxAggregateMiniUnits = source.maxAggregateMiniUnits;
		copy.decisionCount = source.decisionCount;
		copy.acceptedDecisionCount = source.acceptedDecisionCount;
		copy.rejectedDecisionCount = source.rejectedDecisionCount;
		copy.lastDecision = source.lastDecision;
		copy.lastProcessedLiveBarTime = source.lastProcessedLiveBarTime;
		copy.automationCycles = source.automationCycles;
		copy.flattenAttempted = source.flattenAttempted;
		return copy;
	}

	public static String startLive(
		String symbol,
		String executionMode,
		String fundedProfile,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double maxAggregateMiniUnits
	) {
		initializeStore();
		String normalizedMode = normalizeExecutionMode(executionMode);
		FundedRuleProfile profile = fundedRuleProfileFor(fundedProfile);
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		if (snapshot == null) {
			return "{\"success\":false,\"message\":\"Copy Backtest Strategy into the Live Strategy slot before starting the live bot.\",\"status\":" + getLiveStatusJson() + "}";
		}
		if (!"SIMULATED".equals(normalizedMode)) {
			if (!"TOPSTEPX".equals(normalizedMode)) {
				return "{\"success\":false,\"message\":\"Only TopstepX practice order mode can be armed from this live UI.\",\"status\":" + getLiveStatusJson() + "}";
			}
			if (!isOrderSubmissionArmed()) {
				String armResponse = armPracticeOrders(TOPSTEPX_PRACTICE_ACCOUNT_ID, PRACTICE_ORDER_ARM_CONFIRMATION);
				if (!jsonBoolean(armResponse, "success") || !isOrderSubmissionArmed()) {
					return "{\"success\":false,\"message\":"
						+ jsonString("Start Live Bot could not auto-arm TopstepX practice orders: " + jsonStringSummary(armResponse))
						+ ",\"status\":" + getLiveStatusJson() + "}";
				}
			}
			if (!FuturesConnectionManager.isExecutionProviderReady(normalizedMode)) {
				return "{\"success\":false,\"message\":"
					+ jsonString("Live futures execution is not armed for " + normalizedMode + ". Save and test that connection first.")
					+ ",\"status\":" + getLiveStatusJson() + "}";
			}
			if (!ProjectXRealtimeManager.isRunning()) {
				String realtimeStart = ProjectXRealtimeManager.startReadOnly(snapshot.symbols, false, true);
				if (!jsonBoolean(realtimeStart, "success")) {
					disarmPracticeOrders("Practice order submission was guarded because ProjectX realtime did not start.");
					return "{\"success\":false,\"message\":"
						+ jsonString("ProjectX realtime start failed before practice order automation: " + jsonStringSummary(realtimeStart))
						+ ",\"status\":" + getLiveStatusJson() + "}";
				}
			}
			String startedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
			int sessionId = createLiveEngineSession(snapshot, normalizedMode, ProjectXRealtimeManager.currentDataMode(), "RUNNING", "", startedAt);
			synchronized (FuturesManager.class) {
				liveSession.running = true;
				liveSession.symbol = normalizeSymbol(symbol);
				liveSession.sessionId = sessionId;
				liveSession.symbols = snapshot.symbols;
				liveSession.executionMode = normalizedMode;
				liveSession.fundedProfile = profile.code;
				liveSession.accountSize = positiveOrDefault(accountSize, 50000.0);
				liveSession.maxTrailingDrawdown = positiveOrDefault(maxTrailingDrawdown, 2000.0);
				liveSession.dailyLossLimit = positiveOrDefault(dailyLossLimit, 1000.0);
				liveSession.maxRiskPerTrade = positiveOrDefault(maxRiskPerTrade, 400.0);
				int maxAllowedContracts = isTopstep50KProfile(profile.code) ? profileMaxContractsForSymbol(profile, symbol) : 50;
				liveSession.maxContracts = boundedInt(maxContracts, profileMaxContractsForSymbol(profile, symbol), 1, maxAllowedContracts);
				liveSession.maxAggregateMiniUnits = maxAggregateMiniUnits > 0.0 ? clamp(maxAggregateMiniUnits, 0.1, 100.0) : profile.maxAggregateMiniUnits;
				liveSession.startedAt = startedAt;
				liveSession.lastUpdatedAt = liveSession.startedAt;
				liveSession.lastDryRunAt = "";
				liveSession.dataMode = ProjectXRealtimeManager.currentDataMode();
				liveSession.lastBarTime = "";
				liveSession.decisionCount = 0;
				liveSession.acceptedDecisionCount = 0;
				liveSession.rejectedDecisionCount = 0;
				liveSession.lastProcessedLiveBarTime = "";
				liveSession.automationCycles = 0;
				liveSession.flattenAttempted = false;
				MarketSessionStatus marketStatus = currentMarketSessionStatus();
				liveSession.lastDecision = marketStatus.entryWindowOpen
					? "TopstepX practice automation started. Waiting for validated live signals."
					: marketStatus.label + ": " + marketStatus.detail;
			}
			startLiveAutomationLoop();
			return "{\"success\":true,\"message\":"
				+ jsonString("Live futures bot started. ProjectX prices are live and TopstepX 150K practice order automation is enabled.")
				+ ",\"status\":" + getLiveStatusJson() + "}";
		}
		if (!ProjectXRealtimeManager.isRunning()) {
			String realtimeStart = ProjectXRealtimeManager.startReadOnly(snapshot.symbols, false, true);
			if (!jsonBoolean(realtimeStart, "success")) {
				return "{\"success\":false,\"message\":"
					+ jsonString("ProjectX realtime start failed before simulated automation: " + jsonStringSummary(realtimeStart))
					+ ",\"status\":" + getLiveStatusJson() + "}";
			}
		}
		String startedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		int sessionId = createLiveEngineSession(snapshot, normalizedMode, ProjectXRealtimeManager.currentDataMode(), "RUNNING", "", startedAt);
		synchronized (FuturesManager.class) {
			liveSession.running = true;
			liveSession.symbol = normalizeSymbol(symbol);
			liveSession.sessionId = sessionId;
			liveSession.symbols = snapshot.symbols;
			liveSession.executionMode = normalizedMode;
			liveSession.fundedProfile = profile.code;
			liveSession.accountSize = positiveOrDefault(accountSize, 50000.0);
			liveSession.maxTrailingDrawdown = positiveOrDefault(maxTrailingDrawdown, 2000.0);
			liveSession.dailyLossLimit = positiveOrDefault(dailyLossLimit, 1000.0);
			liveSession.maxRiskPerTrade = positiveOrDefault(maxRiskPerTrade, 400.0);
			int maxAllowedContracts = isTopstep50KProfile(profile.code) ? profileMaxContractsForSymbol(profile, symbol) : 50;
			liveSession.maxContracts = boundedInt(maxContracts, profileMaxContractsForSymbol(profile, symbol), 1, maxAllowedContracts);
			liveSession.maxAggregateMiniUnits = maxAggregateMiniUnits > 0.0 ? clamp(maxAggregateMiniUnits, 0.1, 100.0) : profile.maxAggregateMiniUnits;
			liveSession.startedAt = startedAt;
			liveSession.lastUpdatedAt = liveSession.startedAt;
			liveSession.lastDryRunAt = "";
			liveSession.dataMode = ProjectXRealtimeManager.currentDataMode();
			liveSession.lastBarTime = "";
			liveSession.decisionCount = 0;
			liveSession.acceptedDecisionCount = 0;
			liveSession.rejectedDecisionCount = 0;
			liveSession.lastProcessedLiveBarTime = "";
			liveSession.automationCycles = 0;
			liveSession.flattenAttempted = false;
			MarketSessionStatus marketStatus = currentMarketSessionStatus();
			liveSession.lastDecision = marketStatus.entryWindowOpen
				? "Simulated live futures runner started. Live prices are being monitored."
				: marketStatus.label + ": " + marketStatus.detail;
		}
		startLiveAutomationLoop();
		return "{\"success\":true,\"message\":\"Futures simulator started.\",\"status\":" + getLiveStatusJson() + "}";
	}

	public static String stopLive() {
		boolean wasBrokerMode;
		int sessionId;
		stopLiveAutomationLoop();
		synchronized (FuturesManager.class) {
			wasBrokerMode = "TOPSTEPX".equals(liveSession.executionMode);
			sessionId = liveSession.sessionId;
			liveSession.running = false;
			liveSession.lastUpdatedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
			liveSession.lastDecision = "Futures live runner stopped.";
		}
		if (sessionId > 0) {
			updateLiveEngineSession(sessionId, "STOPPED", "", liveSession.decisionCount, liveSession.acceptedDecisionCount, liveSession.rejectedDecisionCount, "Futures live runner stopped.");
		}
		if (wasBrokerMode) {
			disarmPracticeOrders("Practice order submission was guarded because the live runner stopped.");
		}
		return "{\"success\":true,\"message\":\"Futures runner stopped.\",\"status\":" + getLiveStatusJson() + "}";
	}

	public static String getLiveStatusJson() {
		FuturesLiveSession copy;
		synchronized (FuturesManager.class) {
			copy = copyLiveSession(liveSession);
		}
		InstrumentSpec spec = instrumentFor(copy.symbol);
		LiveStrategySnapshotRow snapshot = loadActiveLiveStrategySnapshot();
		boolean orderArmed = isOrderSubmissionArmed();
		MarketSessionStatus marketStatus = currentMarketSessionStatus();
		return "{"
			+ "\"running\":" + copy.running + ","
			+ "\"sessionId\":" + copy.sessionId + ","
			+ "\"symbol\":" + jsonString(copy.symbol) + ","
			+ "\"symbols\":" + jsonString(copy.symbols) + ","
			+ "\"contractName\":" + jsonString(spec.name) + ","
			+ "\"executionMode\":" + jsonString(copy.executionMode) + ","
			+ "\"fundedProfile\":" + jsonString(copy.fundedProfile) + ","
			+ "\"startedAt\":" + jsonString(copy.startedAt) + ","
			+ "\"lastUpdatedAt\":" + jsonString(copy.lastUpdatedAt) + ","
			+ "\"lastDryRunAt\":" + jsonString(copy.lastDryRunAt) + ","
			+ "\"dataMode\":" + jsonString(copy.dataMode) + ","
			+ "\"lastBarTime\":" + jsonString(copy.lastBarTime) + ","
			+ "\"accountSize\":" + round(copy.accountSize) + ","
			+ "\"drawdownCushion\":" + round(copy.maxTrailingDrawdown) + ","
			+ "\"dailyLossLimit\":" + round(copy.dailyLossLimit) + ","
			+ "\"maxRiskPerTrade\":" + round(copy.maxRiskPerTrade) + ","
			+ "\"maxContracts\":" + copy.maxContracts + ","
			+ "\"maxAggregateMiniUnits\":" + round(copy.maxAggregateMiniUnits) + ","
			+ "\"decisionCount\":" + copy.decisionCount + ","
			+ "\"acceptedDecisionCount\":" + copy.acceptedDecisionCount + ","
			+ "\"rejectedDecisionCount\":" + copy.rejectedDecisionCount + ","
			+ "\"automationCycles\":" + copy.automationCycles + ","
			+ "\"lastProcessedLiveBarTime\":" + jsonString(copy.lastProcessedLiveBarTime) + ","
			+ "\"flattenAttempted\":" + copy.flattenAttempted + ","
			+ "\"lastDecision\":" + jsonString(copy.lastDecision) + ","
			+ "\"marketSession\":" + marketSessionJson(marketStatus) + ","
			+ "\"practiceAccountId\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_ID) + ","
			+ "\"accountMode\":" + jsonString(TOPSTEPX_PRACTICE_ACCOUNT_MODE) + ","
			+ "\"orderSubmissionArmed\":" + orderArmed + ","
			+ "\"orderArming\":" + getOrderArmingStatusJson() + ","
			+ "\"liveStrategySnapshot\":" + (snapshot == null ? "null" : liveStrategySnapshotJson(snapshot)) + ","
			+ "\"adapterMessage\":" + jsonString(orderArmed
				? "TopstepX practice order submission is auto-armed for the 150K practice account."
				: "Start Live Bot auto-arms the 150K practice account after pre-flight passes.") + ","
			+ "\"executionOptions\":" + getExecutionOptionsJson()
			+ "}";
	}

	public static String getExecutionOptionsJson() {
		return "["
			+ "{\"code\":\"SIMULATED\",\"name\":\"Built-in Simulation\",\"status\":\"ready\",\"notes\":\"Uses cached futures/proxy bars and funded-rule guardrails. No broker orders.\"},"
			+ "{\"code\":\"TRADOVATE_DIRECT\",\"name\":\"Tradovate API\",\"status\":\"recommended_next\",\"notes\":\"Most practical direct API path after a live Tradovate account, CME license agreement, API add-on, and funded-account permission checks.\"},"
			+ "{\"code\":\"TOPSTEPX\",\"name\":\"TopstepX / ProjectX\",\"status\":\"funded_account_path\",\"notes\":\"Prepared for funded-account API access through ProjectX/TopstepX when credentials, account ID, and device-origin rules are confirmed.\"},"
			+ "{\"code\":\"NINJATRADER_ATI\",\"name\":\"NinjaTrader Bridge\",\"status\":\"possible\",\"notes\":\"Good when the funded firm supports NinjaTrader. External app sends signals to NinjaTrader/NinjaScript for execution.\"},"
			+ "{\"code\":\"RITHMIC\",\"name\":\"Rithmic API\",\"status\":\"advanced\",\"notes\":\"Powerful futures routing and data path, but heavier onboarding and implementation.\"},"
			+ "{\"code\":\"TRADINGVIEW_WEBHOOK\",\"name\":\"TradingView Webhook\",\"status\":\"not_preferred\",\"notes\":\"Useful for alerts, but weaker as the core execution bridge because strategy state, fills, and funded-rule risk should stay inside this app.\"}"
			+ "]";
	}

	public static String getFundedRuleProfilesJson() {
		List<FundedRuleProfile> profiles = fundedRuleProfiles();
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < profiles.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			FundedRuleProfile profile = profiles.get(index);
			json.append("{")
				.append("\"code\":").append(jsonString(profile.code)).append(",")
				.append("\"name\":").append(jsonString(profile.name)).append(",")
				.append("\"provider\":").append(jsonString(profile.provider)).append(",")
				.append("\"accountSize\":").append(profile.accountSize).append(",")
				.append("\"maxTrailingDrawdown\":").append(profile.maxTrailingDrawdown).append(",")
				.append("\"dailyLossLimit\":").append(profile.dailyLossLimit).append(",")
				.append("\"maxRiskPerTrade\":").append(profile.maxRiskPerTrade).append(",")
				.append("\"maxContracts\":").append(profile.maxContracts).append(",")
				.append("\"maxMicroContracts\":").append(profile.maxMicroContracts).append(",")
				.append("\"maxOpenPositions\":").append(profile.maxOpenPositions).append(",")
				.append("\"maxAggregateContracts\":").append(profile.maxAggregateContracts).append(",")
				.append("\"maxAggregateMiniUnits\":").append(profile.maxAggregateMiniUnits).append(",")
				.append("\"profitTarget\":").append(profile.profitTarget).append(",")
				.append("\"trailingDrawdownMode\":").append(jsonString(profile.trailingDrawdownMode)).append(",")
				.append("\"dailySession\":").append(jsonString(profile.dailySession)).append(",")
				.append("\"forceFlatTime\":").append(jsonString(profile.forceFlatTime)).append(",")
				.append("\"notes\":").append(jsonString(profile.notes))
				.append("}");
		}
		json.append("]");
		return json.toString();
	}

	private static List<FundedRuleProfile> fundedRuleProfiles() {
		List<FundedRuleProfile> profiles = new ArrayList<FundedRuleProfile>();
		profiles.add(fundedRuleProfile(
			"TOPSTEP_150K_PRACTICE",
			"Topstep 150K Practice",
			"TOPSTEPX",
			150000.0,
			4500.0,
			3000.0,
			900.0,
			15,
			150,
			3,
			150,
			15.0,
			9000.0,
			"END_OF_DAY",
			"5:00 PM CT to 3:10 PM CT",
			FORCED_EXIT_TIME.toString(),
			"150K practice profile tied to TopstepX practice account " + TOPSTEPX_PRACTICE_ACCOUNT_ID + ". MLL $4,500, DLL $3,000, target $9,000, 15 minis / 150 micros."
		));
		profiles.add(fundedRuleProfile(
			"TOPSTEP_50K_COMBINE",
			"Topstep 50K Combine",
			"TOPSTEPX",
			50000.0,
			2000.0,
			1000.0,
			400.0,
			5,
			50,
			2,
			50,
			5.0,
			3000.0,
			"END_OF_DAY",
			"5:00 PM CT to 3:10 PM CT",
			FORCED_EXIT_TIME.toString(),
			"50K Combine profile: MLL uses end-of-day trailing, DLL is modeled as a strict safety guard, and micros consume 0.1 funded contract units."
		));
		profiles.add(fundedRuleProfile(
			"TOPSTEP_50K_RESEARCH",
			"Topstep 50K Research",
			"TOPSTEPX",
			50000.0,
			2000.0,
			1000.0,
			700.0,
			5,
			50,
			3,
			50,
			5.0,
			0.0,
			"END_OF_DAY",
			"5:00 PM CT to 3:10 PM CT",
			FORCED_EXIT_TIME.toString(),
			"Research profile: same 50K Topstep loss/exposure guards, but no Combine profit-target stop so annual strategy quality can be measured."
		));
		profiles.add(fundedRuleProfile(
			"CUSTOM",
			"Custom Funded Rules",
			"CUSTOM",
			50000.0,
			2000.0,
			1000.0,
			400.0,
			5,
			50,
			1,
			50,
			0.0,
			0.0,
			"INTRADAY",
			"Strategy session",
			FORCED_EXIT_TIME.toString(),
			"Manual profile for non-Topstep settings."
		));
		return profiles;
	}

	private static FundedRuleProfile fundedRuleProfile(
		String code,
		String name,
		String provider,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		int maxMicroContracts,
		int maxOpenPositions,
		int maxAggregateContracts,
		double maxAggregateMiniUnits,
		double profitTarget,
		String trailingDrawdownMode,
		String dailySession,
		String forceFlatTime,
		String notes
	) {
		FundedRuleProfile profile = new FundedRuleProfile();
		profile.code = code;
		profile.name = name;
		profile.provider = provider;
		profile.accountSize = accountSize;
		profile.maxTrailingDrawdown = maxTrailingDrawdown;
		profile.dailyLossLimit = dailyLossLimit;
		profile.maxRiskPerTrade = maxRiskPerTrade;
		profile.maxContracts = maxContracts;
		profile.maxMicroContracts = maxMicroContracts;
		profile.maxOpenPositions = maxOpenPositions;
		profile.maxAggregateContracts = maxAggregateContracts;
		profile.maxAggregateMiniUnits = maxAggregateMiniUnits;
		profile.profitTarget = profitTarget;
		profile.trailingDrawdownMode = trailingDrawdownMode;
		profile.dailySession = dailySession;
		profile.forceFlatTime = forceFlatTime;
		profile.notes = notes;
		return profile;
	}

	private static FundedRuleProfile fundedRuleProfileFor(String code) {
		String normalized = code == null || code.trim().isEmpty() ? "CUSTOM" : code.trim().toUpperCase();
		List<FundedRuleProfile> profiles = fundedRuleProfiles();
		for (int index = 0; index < profiles.size(); index++) {
			if (profiles.get(index).code.equals(normalized)) {
				return profiles.get(index);
			}
		}
		return profiles.get(profiles.size() - 1);
	}

	private static String accountIdForFundedProfile(String code) {
		if ("TOPSTEP_50K_COMBINE".equals(code)) {
			return TOPSTEPX_50K_COMBINE_ACCOUNT_ID;
		}
		return TOPSTEPX_PRACTICE_ACCOUNT_ID;
	}

	private static String accountModeForFundedProfile(String code) {
		if ("TOPSTEP_50K_COMBINE".equals(code)) {
			return "TOPSTEP_50K_COMBINE";
		}
		if ("TOPSTEP_150K_PRACTICE".equals(code)) {
			return "TOPSTEP_150K_PRACTICE";
		}
		return TOPSTEPX_PRACTICE_ACCOUNT_MODE;
	}

	private static int topstepMaxContractsForSymbol(String symbol) {
		return isMicroFuturesSymbol(symbol) ? 50 : 5;
	}

	private static boolean isTopstep50KProfile(String code) {
		return "TOPSTEP_50K_COMBINE".equals(code) || "TOPSTEP_50K_RESEARCH".equals(code) || "TOPSTEP_150K_PRACTICE".equals(code);
	}

	private static int profileMaxContractsForSymbol(FundedRuleProfile profile, String symbol) {
		if (profile == null) {
			return topstepMaxContractsForSymbol(symbol);
		}
		return isMicroFuturesSymbol(symbol) ? profile.maxMicroContracts : profile.maxContracts;
	}

	private static void applyFundedProfile(BacktestConfig config, FundedRuleProfile profile) {
		if (config == null || profile == null || !isTopstep50KProfile(profile.code)) {
			return;
		}
		config.accountSize = profile.accountSize;
		config.maxTrailingDrawdown = profile.maxTrailingDrawdown;
		config.dailyLossLimit = profile.dailyLossLimit;
		config.maxRiskPerTrade = Math.min(config.maxRiskPerTrade, profile.maxRiskPerTrade);
		config.maxContracts = Math.min(config.maxContracts, profileMaxContractsForSymbol(profile, config.symbol));
		config.profitTarget = profile.profitTarget;
		config.trailingDrawdownMode = profile.trailingDrawdownMode;
	}

	private static void applyFundedProfile(PortfolioBacktestConfig config, FundedRuleProfile profile) {
		if (config == null || profile == null || !isTopstep50KProfile(profile.code)) {
			return;
		}
		config.accountSize = profile.accountSize;
		config.maxTrailingDrawdown = profile.maxTrailingDrawdown;
		config.dailyLossLimit = profile.dailyLossLimit;
		config.maxRiskPerTrade = Math.min(config.maxRiskPerTrade, profile.maxRiskPerTrade);
		config.maxContracts = Math.min(config.maxContracts, profile.maxMicroContracts);
		config.maxOpenPositions = Math.min(config.maxOpenPositions, profile.maxOpenPositions);
		config.maxAggregateContracts = Math.min(config.maxAggregateContracts, profile.maxAggregateContracts);
		config.maxAggregateMiniUnits = profile.maxAggregateMiniUnits;
		config.profitTarget = profile.profitTarget;
		config.trailingDrawdownMode = profile.trailingDrawdownMode;
	}

	private static PortfolioBacktestConfig buildPortfolioBacktestConfig(
		String symbols,
		String startDate,
		String endDate,
		double accountSize,
		double maxTrailingDrawdown,
		double dailyLossLimit,
		double maxRiskPerTrade,
		int maxContracts,
		double commissionPerContract,
		double slippageTicks,
		int maxOpenPositions,
		int maxAggregateContracts,
		double maxAggregateMiniUnits,
		boolean useSavedRisk,
		double profitTarget,
		String fundedProfile
	) {
		List<String> parsedSymbols = parseSymbols(symbols);
		if (parsedSymbols.isEmpty()) {
			parsedSymbols.add("MNQ");
		}
		FuturesRiskSettings defaultRisk = loadFuturesRiskSettings(parsedSymbols.get(0));
		FundedRuleProfile profile = fundedRuleProfileFor(fundedProfile);
		PortfolioBacktestConfig config = new PortfolioBacktestConfig();
		config.fundedProfile = profile.code;
		config.symbols = parsedSymbols;
		config.startDate = parseDate(startDate, LocalDate.now().minusYears(1));
		config.endDate = parseDate(endDate, LocalDate.now());
		config.accountSize = positiveOrDefault(accountSize, defaultRisk.accountSize);
		config.maxTrailingDrawdown = positiveOrDefault(maxTrailingDrawdown, defaultRisk.maxTrailingDrawdown);
		config.dailyLossLimit = positiveOrDefault(dailyLossLimit, defaultRisk.dailyLossLimit);
		config.maxRiskPerTrade = positiveOrDefault(maxRiskPerTrade, defaultRisk.maxRiskPerTrade);
		config.maxContracts = boundedInt(maxContracts, defaultRisk.maxContracts, 1, 100);
		config.commissionPerContract = commissionPerContract >= 0.0 ? commissionPerContract : defaultRisk.commissionPerContract;
		config.slippageTicks = slippageTicks >= 0.0 ? slippageTicks : defaultRisk.slippageTicks;
		config.maxOpenPositions = boundedInt(maxOpenPositions, 1, 1, Math.max(1, parsedSymbols.size()));
		config.maxAggregateContracts = boundedInt(maxAggregateContracts, config.maxContracts * Math.max(1, parsedSymbols.size()), 1, 1000);
		config.maxAggregateMiniUnits = maxAggregateMiniUnits > 0.0 ? clamp(maxAggregateMiniUnits, 0.1, 100.0) : profile.maxAggregateMiniUnits;
		config.trailingDrawdownMode = profile.trailingDrawdownMode;
		config.useSavedRisk = useSavedRisk;
		config.profitTarget = profitTarget >= 0.0 ? profitTarget : defaultRisk.profitTarget;
		applyFundedProfile(config, profile);
		return config;
	}

	private static PortfolioBacktestResult runPortfolioBacktest(PortfolioBacktestConfig config) {
		Map<String, PortfolioSymbolContext> contexts = buildPortfolioContexts(config);
		if (contexts.isEmpty()) {
			return null;
		}

		List<LocalDate> days = portfolioDays(contexts);
		if (days.isEmpty()) {
			return null;
		}

		PortfolioBacktestResult result = new PortfolioBacktestResult();
		result.symbols = String.join(",", config.symbols);
		result.fundedProfile = config.fundedProfile;
		result.startDate = days.get(0).toString();
		result.endDate = days.get(days.size() - 1).toString();
		result.startingBalance = round(config.accountSize);
		result.dataSource = "native futures csv portfolio 1min";

		double balance = config.accountSize;
		double peakEquity = balance;
		double trailingReferenceBalance = balance;
		double trailingThreshold = config.accountSize - config.maxTrailingDrawdown;
		double grossProfit = 0.0;
		double grossLoss = 0.0;
		int winners = 0;
		double maxDrawdownPct = 0.0;
		List<PortfolioPosition> openPositions = new ArrayList<PortfolioPosition>();
		boolean endOfDayTrailing = usesEndOfDayTrailing(config.trailingDrawdownMode);

		for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
			LocalDate day = days.get(dayIndex);
			List<LocalTime> times = portfolioTimes(contexts, day);
			if (times.isEmpty()) {
				continue;
			}
			Map<String, Integer> takenByStrategy = new HashMap<String, Integer>();
			double dayStartBalance = balance;
			boolean stopForDay = false;

			for (int timeIndex = 0; timeIndex < times.size(); timeIndex++) {
				LocalTime time = times.get(timeIndex);
				Map<String, Bar> currentBars = barsAt(contexts, day, time);
				if (currentBars.isEmpty()) {
					continue;
				}

				if (!stopForDay && !result.ruleViolation) {
					List<SignalEvent> events = signalEventsAt(contexts, day, time);
					rankPortfolioSignalEvents(events, contexts);
					for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
						SignalEvent event = events.get(eventIndex);
						PortfolioSymbolContext context = contexts.get(event.symbol);
						Bar entryBar = currentBars.get(event.symbol);
						if (context == null || entryBar == null) {
							continue;
						}
						if (hasOpenSymbol(openPositions, event.symbol) || openPositions.size() >= config.maxOpenPositions) {
							result.overlapRejections++;
							continue;
						}
						int openContracts = openContractCount(openPositions);
						double openMiniUnits = openMiniUnitCount(openPositions);
						if (openContracts >= config.maxAggregateContracts || fundedMiniUnitLimitReached(config, openMiniUnits)) {
							result.exposureRejections++;
							continue;
						}
						String strategyKey = event.symbol + "|" + event.signal.strategyCode;
						int taken = countFor(takenByStrategy, strategyKey);
						if (taken >= maxTradesPerDay(event.signal.strategyCode, context.config.strategySettings)) {
							continue;
						}

						double equityAtOpen = balance + aggregateOpenPnl(openPositions, currentBars, "open");
						if (equityAtOpen - dayStartBalance <= -Math.abs(config.dailyLossLimit)) {
							stopForDay = true;
							break;
						}
						if (equityAtOpen <= trailingThreshold) {
							result.ruleViolation = true;
							result.ruleMessage = "Portfolio trailing drawdown threshold breached before entry.";
							result.trailingDrawdownBreaches++;
							break;
						}

						double reservedOpenRisk = aggregateOpenRisk(openPositions);
						double dailyRiskBudget = Math.abs(config.dailyLossLimit) + (equityAtOpen - dayStartBalance) - reservedOpenRisk;
						double trailingRiskBudget = equityAtOpen - trailingThreshold - reservedOpenRisk;
						double aggregateGuardBudget = Math.min(dailyRiskBudget, trailingRiskBudget);
						double riskBudgetMultiplier = portfolioRiskBudgetMultiplier(
							context,
							event,
							balance - dayStartBalance,
							aggregateGuardBudget,
							openPositions.size()
						);
						double symbolRiskBudget = context.config.maxRiskPerTrade * riskBudgetMultiplier;
						double availableRiskBudget = Math.min(symbolRiskBudget, aggregateGuardBudget);
						int aggregateRoom = config.maxAggregateContracts - openContracts;
						if (config.maxAggregateMiniUnits > 0.0) {
							aggregateRoom = Math.min(aggregateRoom, contractsAllowedByMiniUnitRoom(event.symbol, config.maxAggregateMiniUnits - openMiniUnits));
						}
						if (availableRiskBudget <= 0.0 || aggregateRoom <= 0) {
							result.riskRejections++;
							continue;
						}

						PortfolioPosition position = openPortfolioPosition(context, event, entryBar, availableRiskBudget, aggregateRoom, aggregateGuardBudget);
						if (position == null) {
							result.riskRejections++;
							continue;
						}
						position.concurrentPositionsAtEntry = openPositions.size() + 1;
						position.concurrentContractsAtEntry = openContracts + position.contracts;
						openPositions.add(position);
						takenByStrategy.put(strategyKey, taken + 1);
						updatePortfolioExposureMetrics(result, openPositions);
					}
				}

				updateOpenPositionExcursions(openPositions, currentBars);
				double worstOpenPnl = aggregateWorstOpenPnl(openPositions, currentBars);
				result.maxAggregateMae = round(Math.min(result.maxAggregateMae, worstOpenPnl));
				double worstEquity = balance + worstOpenPnl;
				double worstIntraday = worstEquity - dayStartBalance;
				result.maxIntradayLoss = round(Math.min(result.maxIntradayLoss, worstIntraday));
				if (!openPositions.isEmpty() && worstIntraday <= -Math.abs(config.dailyLossLimit)) {
					result.ruleViolation = true;
					result.ruleMessage = "Portfolio daily loss limit breached intratrade.";
					result.dailyLossBreaches++;
					result.maeBreaches++;
				}
				if (!openPositions.isEmpty() && worstEquity <= trailingThreshold) {
					result.ruleViolation = true;
					result.ruleMessage = "Portfolio trailing drawdown breached intratrade.";
					result.trailingDrawdownBreaches++;
					result.maeBreaches++;
				}

				List<FuturesTrade> closedTrades = closeTriggeredPortfolioPositions(openPositions, contexts, currentBars, day, time);
				for (int closedIndex = 0; closedIndex < closedTrades.size(); closedIndex++) {
					FuturesTrade trade = closedTrades.get(closedIndex);
					balance = round(balance + trade.pnl);
					if (trade.pnl >= 0.0) {
						winners++;
						grossProfit = round(grossProfit + trade.pnl);
					} else {
						grossLoss = round(grossLoss + Math.abs(trade.pnl));
					}
					result.tradeRecords.add(trade);
				}

				if (result.ruleViolation) {
					List<FuturesTrade> forcedTrades = forceClosePortfolioPositions(openPositions, contexts, currentBars, day, time, "Portfolio rule breach flat exit");
					for (int forcedIndex = 0; forcedIndex < forcedTrades.size(); forcedIndex++) {
						FuturesTrade trade = forcedTrades.get(forcedIndex);
						balance = round(balance + trade.pnl);
						if (trade.pnl >= 0.0) {
							winners++;
							grossProfit = round(grossProfit + trade.pnl);
						} else {
							grossLoss = round(grossLoss + Math.abs(trade.pnl));
						}
						result.tradeRecords.add(trade);
					}
				}

				double currentEquity = balance + aggregateOpenPnl(openPositions, currentBars, "close");
				peakEquity = Math.max(peakEquity, currentEquity);
				if (!endOfDayTrailing) {
					trailingThreshold = Math.max(trailingThreshold, peakEquity - config.maxTrailingDrawdown);
				}
				double drawdownPct = peakEquity <= 0.0 ? 0.0 : ((peakEquity - currentEquity) / peakEquity) * 100.0;
				maxDrawdownPct = Math.max(maxDrawdownPct, drawdownPct);
				double currentIntraday = currentEquity - dayStartBalance;
				result.maxIntradayLoss = round(Math.min(result.maxIntradayLoss, currentIntraday));
				if (currentIntraday <= -Math.abs(config.dailyLossLimit)) {
					result.ruleViolation = true;
					result.ruleMessage = "Portfolio daily loss limit breached.";
					result.dailyLossBreaches++;
				}
				if (currentEquity <= trailingThreshold) {
					result.ruleViolation = true;
					result.ruleMessage = "Portfolio trailing drawdown threshold breached.";
					result.trailingDrawdownBreaches++;
				}
				if (config.profitTarget > 0.0 && currentEquity - config.accountSize >= config.profitTarget) {
					result.ruleMessage = "Portfolio profit target reached.";
					stopForDay = true;
				}
				updatePortfolioExposureMetrics(result, openPositions);
				if (result.ruleViolation || "Portfolio profit target reached.".equals(result.ruleMessage)) {
					break;
				}
			}

			if (!openPositions.isEmpty()) {
				Map<String, Bar> closingBars = lastBarsForDay(contexts, day);
				List<FuturesTrade> forcedTrades = forceClosePortfolioPositions(openPositions, contexts, closingBars, day, times.get(times.size() - 1), "Portfolio end-of-day flat exit");
				for (int forcedIndex = 0; forcedIndex < forcedTrades.size(); forcedIndex++) {
					FuturesTrade trade = forcedTrades.get(forcedIndex);
					balance = round(balance + trade.pnl);
					if (trade.pnl >= 0.0) {
						winners++;
						grossProfit = round(grossProfit + trade.pnl);
					} else {
						grossLoss = round(grossLoss + Math.abs(trade.pnl));
					}
					result.tradeRecords.add(trade);
				}
			}
			if (result.ruleViolation || "Portfolio profit target reached.".equals(result.ruleMessage)) {
				break;
			}
			if (endOfDayTrailing) {
				trailingReferenceBalance = Math.max(trailingReferenceBalance, balance);
				trailingThreshold = endOfDayTrailingThreshold(trailingThreshold, trailingReferenceBalance, config.accountSize, config.maxTrailingDrawdown);
			}
		}

		result.endingBalance = round(balance);
		result.totalProfit = round(balance - config.accountSize);
		result.returnPct = config.accountSize <= 0.0 ? 0.0 : round((result.totalProfit / config.accountSize) * 100.0);
		result.trades = result.tradeRecords.size();
		result.winRate = result.trades == 0 ? 0.0 : round((winners * 100.0) / result.trades);
		result.profitFactor = grossLoss == 0.0 ? round(grossProfit) : round(grossProfit / grossLoss);
		result.maxDrawdownPct = round(maxDrawdownPct);
		result.trailingThreshold = round(trailingThreshold);
		if (result.ruleMessage == null || result.ruleMessage.trim().isEmpty()) {
			result.ruleMessage = result.ruleViolation ? "Portfolio funded rule violation." : "Completed without portfolio funded-rule breach.";
		}
		return result;
	}

	private static Map<String, PortfolioSymbolContext> buildPortfolioContexts(PortfolioBacktestConfig config) {
		Map<String, PortfolioSymbolContext> contexts = new TreeMap<String, PortfolioSymbolContext>();
		for (int symbolIndex = 0; symbolIndex < config.symbols.size(); symbolIndex++) {
			String symbol = normalizeSymbol(config.symbols.get(symbolIndex));
			DataBundle bars = loadNativeFuturesBars(symbol, config.startDate, config.endDate, TIMEFRAME_FOLDER);
			if (bars.bars.isEmpty()) {
				return new TreeMap<String, PortfolioSymbolContext>();
			}

			PortfolioSymbolContext context = new PortfolioSymbolContext();
			context.symbol = symbol;
			context.spec = instrumentFor(symbol);
			context.bars = bars;
			context.config = buildBacktestConfig(
				symbol,
				config.startDate.toString(),
				config.endDate.toString(),
				config.accountSize,
				config.maxTrailingDrawdown,
				config.dailyLossLimit,
				config.maxRiskPerTrade,
				config.maxContracts,
				config.commissionPerContract,
				config.slippageTicks,
				config.profitTarget
			);
			context.config.strategySettings = loadFuturesStrategySettings(symbol, config.strategySlot);
			if (config.useSavedRisk) {
				FuturesRiskSettings savedRisk = loadFuturesRiskSettings(symbol);
				context.config.maxRiskPerTrade = savedRisk.maxRiskPerTrade;
				context.config.maxContracts = savedRisk.maxContracts;
			}
			if (isTopstep50KProfile(config.fundedProfile)) {
				context.config.maxRiskPerTrade = Math.min(context.config.maxRiskPerTrade, fundedRuleProfileFor(config.fundedProfile).maxRiskPerTrade);
				context.config.maxContracts = Math.min(context.config.maxContracts, profileMaxContractsForSymbol(fundedRuleProfileFor(config.fundedProfile), symbol));
			}
			context.config.commissionPerContract = config.commissionPerContract;
			context.config.slippageTicks = config.slippageTicks;
			context.config.accountSize = config.accountSize;
			context.config.maxTrailingDrawdown = config.maxTrailingDrawdown;
			context.config.dailyLossLimit = config.dailyLossLimit;
			context.byDay = groupByDay(bars.bars);
			context.fifteenMinuteByDay = groupByDay(loadNativeFuturesBars(symbol, config.startDate, config.endDate, "15min").bars);
			context.oneHourByDay = groupByDay(loadNativeFuturesBars(symbol, config.startDate, config.endDate, "1hour").bars);
			context.indexByDayTime = indexBarsByDayTime(context.byDay);
			preparePortfolioSignalEvents(context);
			contexts.put(symbol, context);
		}
		return contexts;
	}

	private static Map<LocalDate, Map<LocalTime, Integer>> indexBarsByDayTime(Map<LocalDate, List<Bar>> byDay) {
		Map<LocalDate, Map<LocalTime, Integer>> index = new HashMap<LocalDate, Map<LocalTime, Integer>>();
		for (LocalDate day : byDay.keySet()) {
			List<Bar> bars = byDay.get(day);
			Map<LocalTime, Integer> byTime = new HashMap<LocalTime, Integer>();
			for (int barIndex = 0; barIndex < bars.size(); barIndex++) {
				byTime.put(bars.get(barIndex).marketTime, barIndex);
			}
			index.put(day, byTime);
		}
		return index;
	}

	private static void preparePortfolioSignalEvents(PortfolioSymbolContext context) {
		List<LocalDate> days = new ArrayList<LocalDate>(context.byDay.keySet());
		Collections.sort(days);
		for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
			LocalDate day = days.get(dayIndex);
			List<Bar> bars = context.byDay.get(day);
			if (bars == null || bars.size() < 40) {
				continue;
			}
			List<Signal> signals = buildSignals(
				context.spec,
				bars,
				previousDayBars(context.byDay, days, dayIndex),
				context.fifteenMinuteByDay.get(day),
				context.oneHourByDay.get(day),
				context.config
			);
			List<SignalEvent> events = new ArrayList<SignalEvent>();
			for (int signalIndex = 0; signalIndex < signals.size(); signalIndex++) {
				Signal signal = signals.get(signalIndex);
				int executionIndex = signal.entryIndex + 1;
				if (executionIndex >= bars.size()) {
					continue;
				}
				Bar entryBar = bars.get(executionIndex);
				if (!entryBar.marketTime.isBefore(FORCED_EXIT_TIME)) {
					continue;
				}
				SignalEvent event = new SignalEvent();
				event.symbol = context.symbol;
				event.signal = signal;
				event.day = day;
				event.entryTime = entryBar.marketTime;
				event.executionIndex = executionIndex;
				events.add(event);
			}
			Collections.sort(events, new Comparator<SignalEvent>() {
				@Override
				public int compare(SignalEvent first, SignalEvent second) {
					int timeCompare = first.entryTime.compareTo(second.entryTime);
					if (timeCompare != 0) {
						return timeCompare;
					}
					return first.symbol.compareTo(second.symbol);
				}
			});
			context.eventsByDay.put(day, events);
		}
	}

	private static List<LocalDate> portfolioDays(Map<String, PortfolioSymbolContext> contexts) {
		Set<LocalDate> days = new HashSet<LocalDate>();
		for (PortfolioSymbolContext context : contexts.values()) {
			days.addAll(context.byDay.keySet());
		}
		List<LocalDate> sorted = new ArrayList<LocalDate>(days);
		Collections.sort(sorted);
		return sorted;
	}

	private static List<LocalTime> portfolioTimes(Map<String, PortfolioSymbolContext> contexts, LocalDate day) {
		Set<LocalTime> times = new HashSet<LocalTime>();
		for (PortfolioSymbolContext context : contexts.values()) {
			Map<LocalTime, Integer> index = context.indexByDayTime.get(day);
			if (index != null) {
				times.addAll(index.keySet());
			}
		}
		List<LocalTime> sorted = new ArrayList<LocalTime>(times);
		Collections.sort(sorted);
		return sorted;
	}

	private static Map<String, Bar> barsAt(Map<String, PortfolioSymbolContext> contexts, LocalDate day, LocalTime time) {
		Map<String, Bar> bars = new HashMap<String, Bar>();
		for (PortfolioSymbolContext context : contexts.values()) {
			Integer index = barIndex(context, day, time);
			List<Bar> dayBars = context.byDay.get(day);
			if (index != null && dayBars != null && index >= 0 && index < dayBars.size()) {
				bars.put(context.symbol, dayBars.get(index));
			}
		}
		return bars;
	}

	private static Map<String, Bar> lastBarsForDay(Map<String, PortfolioSymbolContext> contexts, LocalDate day) {
		Map<String, Bar> bars = new HashMap<String, Bar>();
		for (PortfolioSymbolContext context : contexts.values()) {
			List<Bar> dayBars = context.byDay.get(day);
			if (dayBars != null && !dayBars.isEmpty()) {
				bars.put(context.symbol, dayBars.get(dayBars.size() - 1));
			}
		}
		return bars;
	}

	private static Integer barIndex(PortfolioSymbolContext context, LocalDate day, LocalTime time) {
		Map<LocalTime, Integer> byTime = context.indexByDayTime.get(day);
		return byTime == null ? null : byTime.get(time);
	}

	private static List<SignalEvent> signalEventsAt(Map<String, PortfolioSymbolContext> contexts, LocalDate day, LocalTime time) {
		List<SignalEvent> events = new ArrayList<SignalEvent>();
		for (PortfolioSymbolContext context : contexts.values()) {
			List<SignalEvent> dayEvents = context.eventsByDay.get(day);
			if (dayEvents == null) {
				continue;
			}
			for (int index = 0; index < dayEvents.size(); index++) {
				SignalEvent event = dayEvents.get(index);
				if (time.equals(event.entryTime)) {
					events.add(event);
				}
			}
		}
		Collections.sort(events, new Comparator<SignalEvent>() {
			@Override
			public int compare(SignalEvent first, SignalEvent second) {
				return first.symbol.compareTo(second.symbol);
			}
		});
		return events;
	}

	private static void rankPortfolioSignalEvents(final List<SignalEvent> events, final Map<String, PortfolioSymbolContext> contexts) {
		Collections.sort(events, new Comparator<SignalEvent>() {
			@Override
			public int compare(SignalEvent first, SignalEvent second) {
				double firstScore = portfolioEventScore(contexts.get(first.symbol), first);
				double secondScore = portfolioEventScore(contexts.get(second.symbol), second);
				int scoreCompare = Double.compare(secondScore, firstScore);
				if (scoreCompare != 0) {
					return scoreCompare;
				}
				int timeCompare = first.entryTime.compareTo(second.entryTime);
				if (timeCompare != 0) {
					return timeCompare;
				}
				return first.symbol.compareTo(second.symbol);
			}
		});
	}

	private static double portfolioEventScore(PortfolioSymbolContext context, SignalEvent event) {
		if (context == null || event == null || event.signal == null) {
			return 0.0;
		}
		Signal signal = event.signal;
		Bar signalBar = signalBarForEvent(context, event);
		double signalRisk = Math.abs(signal.entryPrice - signal.stopPrice);
		double rewardRisk = signalRisk <= 0.0 ? context.spec.defaultTargetR : Math.abs(signal.targetPrice - signal.entryPrice) / signalRisk;
		double riskTicks = signalRisk <= 0.0 ? 999.0 : signalRisk / context.spec.tickSize;
		double score = 50.0 + (clamp(rewardRisk, 0.0, 3.0) * 5.0) - Math.min(25.0, riskTicks * 0.35);
		if (isFrequencyExpansionStrategy(signal.strategyCode)) {
			score -= 2.0;
		}
		if (signalBar != null) {
			double volumeRatio = signalBar.volumeSma20 <= 0.0 ? 1.0 : signalBar.volume / signalBar.volumeSma20;
			if (volumeRatio >= 1.25) {
				score += 3.0;
			}
			if (signalBar.bodyPct >= 35.0) {
				score += 2.0;
			}
			if (emaTrendAligned(signal.side, signalBar)) {
				score += 3.0;
			}
			if ("LONG".equals(signal.side) && closeLocation(signalBar) >= 0.65) {
				score += 1.0;
			}
			if ("SHORT".equals(signal.side) && closeLocation(signalBar) <= 0.35) {
				score += 1.0;
			}
		}
		return score;
	}

	private static Bar signalBarForEvent(PortfolioSymbolContext context, SignalEvent event) {
		if (context == null || event == null || event.signal == null) {
			return null;
		}
		List<Bar> bars = context.byDay.get(event.day);
		if (bars == null || bars.isEmpty()) {
			return null;
		}
		int signalIndex = Math.max(0, Math.min(event.signal.entryIndex, bars.size() - 1));
		return bars.get(signalIndex);
	}

	private static double portfolioRiskBudgetMultiplier(
		PortfolioSymbolContext context,
		SignalEvent event,
		double realizedDayPnl,
		double aggregateGuardBudget,
		int openPositions
	) {
		if (context == null || event == null || event.signal == null) {
			return 0.0;
		}
		Signal signal = event.signal;
		double signalRisk = Math.abs(signal.entryPrice - signal.stopPrice);
		double riskTicks = signalRisk <= 0.0 ? 999.0 : signalRisk / context.spec.tickSize;
		double multiplier = signalQualityRiskMultiplier(context, event);
		if (riskTicks <= 12.0) {
			multiplier *= 1.12;
		} else if (riskTicks <= 24.0) {
			multiplier *= 1.0;
		} else if (riskTicks <= 48.0) {
			multiplier *= 0.82;
		} else {
			multiplier *= 0.60;
		}
		if (isFrequencyExpansionStrategy(signal.strategyCode)) {
			multiplier *= 0.45;
		}

		if (realizedDayPnl >= 750.0 && aggregateGuardBudget > 750.0) {
			multiplier *= 1.22;
		} else if (realizedDayPnl >= 300.0 && aggregateGuardBudget > 450.0) {
			multiplier *= 1.12;
		} else if (realizedDayPnl < -150.0) {
			multiplier *= 0.65;
		} else if (realizedDayPnl < 0.0) {
			multiplier *= 0.85;
		}
		if (openPositions > 0 && aggregateGuardBudget < 350.0) {
			multiplier *= 0.65;
		}
		if (aggregateGuardBudget < 175.0) {
			multiplier *= 0.5;
		}
		return clamp(multiplier, 0.0, 1.35);
	}

	private static double signalQualityRiskMultiplier(PortfolioSymbolContext context, SignalEvent event) {
		Bar signalBar = signalBarForEvent(context, event);
		if (signalBar == null || event == null || event.signal == null) {
			return 0.9;
		}
		double multiplier = 0.92;
		double volumeRatio = signalBar.volumeSma20 <= 0.0 ? 1.0 : signalBar.volume / signalBar.volumeSma20;
		if (volumeRatio >= 1.25) {
			multiplier += 0.08;
		}
		if (signalBar.bodyPct >= 35.0) {
			multiplier += 0.06;
		}
		if (emaTrendAligned(event.signal.side, signalBar)) {
			multiplier += 0.08;
		}
		return clamp(multiplier, 0.75, 1.12);
	}

	private static boolean isFrequencyExpansionStrategy(String strategyCode) {
		return "OMOM".equals(strategyCode)
			|| "AFT".equals(strategyCode)
			|| "MIM".equals(strategyCode)
			|| "IPB".equals(strategyCode)
			|| "CMOM".equals(strategyCode)
			|| "MSCALP".equals(strategyCode)
			|| "KELT".equals(strategyCode)
			|| "KREV".equals(strategyCode);
	}

	private static boolean hasOpenSymbol(List<PortfolioPosition> positions, String symbol) {
		for (int index = 0; index < positions.size(); index++) {
			if (positions.get(index).symbol.equals(symbol)) {
				return true;
			}
		}
		return false;
	}

	private static int openContractCount(List<PortfolioPosition> positions) {
		int contracts = 0;
		for (int index = 0; index < positions.size(); index++) {
			contracts += positions.get(index).contracts;
		}
		return contracts;
	}

	private static double openMiniUnitCount(List<PortfolioPosition> positions) {
		double units = 0.0;
		for (int index = 0; index < positions.size(); index++) {
			PortfolioPosition position = positions.get(index);
			units += fundedMiniUnitsPerContract(position.symbol) * position.contracts;
		}
		return units;
	}

	private static boolean fundedMiniUnitLimitReached(PortfolioBacktestConfig config, double openMiniUnits) {
		return config.maxAggregateMiniUnits > 0.0 && openMiniUnits >= config.maxAggregateMiniUnits - 0.000001;
	}

	private static int contractsAllowedByMiniUnitRoom(String symbol, double room) {
		if (room <= 0.0) {
			return 0;
		}
		return (int) Math.floor((room + 0.000001) / fundedMiniUnitsPerContract(symbol));
	}

	private static double fundedMiniUnitsPerContract(String symbol) {
		return isMicroFuturesSymbol(symbol) ? 0.1 : 1.0;
	}

	private static PortfolioPosition openPortfolioPosition(
		PortfolioSymbolContext context,
		SignalEvent event,
		Bar entryBar,
		double riskBudget,
		int aggregateRoom,
		double aggregateGuardBudget
	) {
		Signal signal = event.signal;
		double signalRisk = Math.abs(signal.entryPrice - signal.stopPrice);
		if (signalRisk <= 0.0) {
			return null;
		}
		double rewardMultiple = Math.abs(signal.targetPrice - signal.entryPrice) / signalRisk;
		if (rewardMultiple <= 0.0 || Double.isNaN(rewardMultiple) || Double.isInfinite(rewardMultiple)) {
			rewardMultiple = context.spec.defaultTargetR;
		}

		double entryPrice = applySlippage(context.spec, entryBar.open, signal.side, context.config.slippageTicks, true);
		double stopPrice = roundToTick(context.spec, signal.stopPrice);
		if ("LONG".equals(signal.side) && entryPrice <= stopPrice) {
			return null;
		}
		if ("SHORT".equals(signal.side) && entryPrice >= stopPrice) {
			return null;
		}

		double initialRisk = Math.abs(entryPrice - stopPrice);
		double rawRiskTicks = initialRisk / context.spec.tickSize;
		if (rawRiskTicks < 1.0) {
			return null;
		}
		FuturesStrategySettings settings = context.config.strategySettings == null ? defaultFuturesStrategySettings() : context.config.strategySettings;
		if (rawRiskTicks > settings.maxInitialRiskTicks) {
			return null;
		}
		List<Bar> dayBars = context.byDay.get(event.day);
		rewardMultiple = adaptiveRewardMultiple(context.spec, dayBars, signal, event.executionIndex, rewardMultiple, settings);

		double stopFillForRisk = applySlippage(context.spec, stopPrice, signal.side, context.config.slippageTicks, false);
		double riskPerContract = (Math.abs(entryPrice - stopFillForRisk) / context.spec.tickSize * context.spec.tickValue) + (context.config.commissionPerContract * 2.0);
		double sizingRiskPerContract = riskPerContract * settings.openMaeRiskMultiplier;
		double effectiveRiskBudget = Math.max(0.0, riskBudget);
		int contracts = Math.min(context.config.maxContracts, Math.min(aggregateRoom, (int) Math.floor(effectiveRiskBudget / Math.max(1.0, riskPerContract))));
		while (contracts > 0 && (sizingRiskPerContract * contracts) > Math.max(0.0, aggregateGuardBudget)) {
			contracts--;
		}
		if (contracts < 1) {
			return null;
		}

		double targetPrice = "LONG".equals(signal.side)
			? roundToTick(context.spec, entryPrice + (initialRisk * rewardMultiple))
			: roundToTick(context.spec, entryPrice - (initialRisk * rewardMultiple));

		PortfolioPosition position = new PortfolioPosition();
		position.symbol = context.symbol;
		position.spec = context.spec;
		position.signal = signal;
		position.side = signal.side;
		position.contracts = contracts;
		position.entryPrice = roundToTick(context.spec, entryPrice);
		position.stopPrice = roundToTick(context.spec, stopPrice);
		position.activeStopPrice = position.stopPrice;
		position.targetPrice = roundToTick(context.spec, targetPrice);
		position.initialRisk = initialRisk;
		position.minTrailDistance = Math.max(context.spec.tickSize * 8.0, initialRisk * 0.55);
		position.riskPerContract = riskPerContract;
		position.commissionPerContract = context.config.commissionPerContract;
		position.entryIndex = event.executionIndex;
		position.openedAt = entryBar.displayTime;
		position.openedMarketTime = entryBar.marketTime;
		return position;
	}

	private static void updateOpenPositionExcursions(List<PortfolioPosition> positions, Map<String, Bar> currentBars) {
		for (int index = 0; index < positions.size(); index++) {
			PortfolioPosition position = positions.get(index);
			Bar bar = currentBars.get(position.symbol);
			if (bar == null) {
				continue;
			}
			double favorable = favorablePnl(position.spec, position.side, position.entryPrice, bar, position.contracts);
			double adverse = adversePnl(position.spec, position.side, position.entryPrice, bar, position.contracts);
			position.maxFavorable = Math.max(position.maxFavorable, favorable);
			position.maxAdverse = Math.min(position.maxAdverse, adverse);
		}
	}

	private static double aggregateOpenRisk(List<PortfolioPosition> positions) {
		double risk = 0.0;
		for (int index = 0; index < positions.size(); index++) {
			PortfolioPosition position = positions.get(index);
			risk += position.riskPerContract * position.contracts;
		}
		return round(risk);
	}

	private static double aggregateOpenPnl(List<PortfolioPosition> positions, Map<String, Bar> currentBars, String priceField) {
		double pnl = 0.0;
		for (int index = 0; index < positions.size(); index++) {
			PortfolioPosition position = positions.get(index);
			Bar bar = currentBars.get(position.symbol);
			if (bar == null) {
				continue;
			}
			double mark = "open".equals(priceField) ? bar.open : bar.close;
			pnl += pnlForPrice(position.spec, position.side, position.entryPrice, mark, position.contracts) - roundTripCommission(position);
		}
		return round(pnl);
	}

	private static double aggregateWorstOpenPnl(List<PortfolioPosition> positions, Map<String, Bar> currentBars) {
		double pnl = 0.0;
		for (int index = 0; index < positions.size(); index++) {
			PortfolioPosition position = positions.get(index);
			Bar bar = currentBars.get(position.symbol);
			if (bar == null) {
				continue;
			}
			pnl += adversePnl(position.spec, position.side, position.entryPrice, bar, position.contracts) - roundTripCommission(position);
		}
		return round(pnl);
	}

	private static double roundTripCommission(PortfolioPosition position) {
		return position.commissionPerContract * position.contracts * 2.0;
	}

	private static void updatePortfolioExposureMetrics(PortfolioBacktestResult result, List<PortfolioPosition> positions) {
		int contracts = 0;
		double miniUnits = 0.0;
		double notionalExposure = 0.0;
		for (int index = 0; index < positions.size(); index++) {
			PortfolioPosition position = positions.get(index);
			contracts += position.contracts;
			miniUnits += fundedMiniUnitsPerContract(position.symbol) * position.contracts;
			notionalExposure += Math.abs(position.entryPrice * position.spec.pointValue * position.contracts);
		}
		result.maxConcurrentPositions = Math.max(result.maxConcurrentPositions, positions.size());
		result.maxConcurrentContracts = Math.max(result.maxConcurrentContracts, contracts);
		result.maxConcurrentMiniUnits = round(Math.max(result.maxConcurrentMiniUnits, miniUnits));
		result.maxNotionalExposure = round(Math.max(result.maxNotionalExposure, notionalExposure));
	}

	private static List<FuturesTrade> closeTriggeredPortfolioPositions(
		List<PortfolioPosition> positions,
		Map<String, PortfolioSymbolContext> contexts,
		Map<String, Bar> currentBars,
		LocalDate day,
		LocalTime time
	) {
		List<FuturesTrade> trades = new ArrayList<FuturesTrade>();
		for (int index = positions.size() - 1; index >= 0; index--) {
			PortfolioPosition position = positions.get(index);
			PortfolioSymbolContext context = contexts.get(position.symbol);
			Bar bar = currentBars.get(position.symbol);
			if (context == null || bar == null) {
				continue;
			}
			FuturesTrade trade = closePortfolioPositionIfTriggered(position, context, bar, day, time);
			if (trade != null) {
				trades.add(0, trade);
				positions.remove(index);
			}
		}
		return trades;
	}

	private static FuturesTrade closePortfolioPositionIfTriggered(
		PortfolioPosition position,
		PortfolioSymbolContext context,
		Bar bar,
		LocalDate day,
		LocalTime time
	) {
		Integer currentIndexValue = barIndex(context, day, time);
		if (currentIndexValue == null || currentIndexValue < position.entryIndex) {
			return null;
		}
		int currentIndex = currentIndexValue.intValue();
		double exitPrice = bar.close;
		String exitReason = null;
		if ("LONG".equals(position.side)) {
			if (bar.low <= position.activeStopPrice && bar.high >= position.targetPrice) {
				exitPrice = position.activeStopPrice;
				exitReason = "Stop and target touched; stop assumed first";
			} else if (bar.low <= position.activeStopPrice) {
				exitPrice = position.activeStopPrice;
				exitReason = position.activeStopPrice == position.stopPrice ? "Stop loss hit" : "Managed stop hit";
			} else if (bar.high >= position.targetPrice) {
				exitPrice = position.targetPrice;
				exitReason = "Target reached";
			}
		} else {
			if (bar.high >= position.activeStopPrice && bar.low <= position.targetPrice) {
				exitPrice = position.activeStopPrice;
				exitReason = "Stop and target touched; stop assumed first";
			} else if (bar.high >= position.activeStopPrice) {
				exitPrice = position.activeStopPrice;
				exitReason = position.activeStopPrice == position.stopPrice ? "Stop loss hit" : "Managed stop hit";
			} else if (bar.low <= position.targetPrice) {
				exitPrice = position.targetPrice;
				exitReason = "Target reached";
			}
		}
		FuturesStrategySettings settings = context.config.strategySettings == null ? defaultFuturesStrategySettings() : context.config.strategySettings;
		if (exitReason == null && shouldEarlyLossCut(position.spec, settings, position.side, position.entryPrice, position.initialRisk, bar, position.contracts, currentIndex - position.entryIndex)) {
			exitPrice = bar.close;
			exitReason = "Adaptive loss cut exit";
		}
		if (exitReason == null && !bar.marketTime.isBefore(FORCED_EXIT_TIME)) {
			exitPrice = bar.close;
			exitReason = "Funded-session flat exit";
		}
		if (exitReason == null && position.signal.maxHoldBars > 0 && currentIndex - position.entryIndex >= position.signal.maxHoldBars) {
			exitPrice = bar.close;
			exitReason = "Time stop exit";
		}
		if (exitReason == null) {
			position.activeStopPrice = updateManagedStop(position.spec, position.side, position.entryPrice, position.activeStopPrice, bar.close, position.initialRisk, position.minTrailDistance);
			return null;
		}
		return buildPortfolioTrade(position, context, bar, currentIndex, exitPrice, exitReason);
	}

	private static List<FuturesTrade> forceClosePortfolioPositions(
		List<PortfolioPosition> positions,
		Map<String, PortfolioSymbolContext> contexts,
		Map<String, Bar> currentBars,
		LocalDate day,
		LocalTime time,
		String reason
	) {
		List<FuturesTrade> trades = new ArrayList<FuturesTrade>();
		for (int index = 0; index < positions.size(); index++) {
			PortfolioPosition position = positions.get(index);
			PortfolioSymbolContext context = contexts.get(position.symbol);
			Bar bar = currentBars.get(position.symbol);
			if (context == null || bar == null) {
				continue;
			}
			Integer currentIndexValue = barIndex(context, day, time);
			int currentIndex = currentIndexValue == null ? position.entryIndex : currentIndexValue.intValue();
			trades.add(buildPortfolioTrade(position, context, bar, currentIndex, bar.close, reason));
		}
		positions.clear();
		return trades;
	}

	private static FuturesTrade buildPortfolioTrade(
		PortfolioPosition position,
		PortfolioSymbolContext context,
		Bar bar,
		int exitIndex,
		double rawExitPrice,
		String exitReason
	) {
		double exitPrice = applySlippage(position.spec, rawExitPrice, position.side, context.config.slippageTicks, false);
		double grossPnl = pnlForPrice(position.spec, position.side, position.entryPrice, exitPrice, position.contracts);
		double commissions = roundTripCommission(position);

		FuturesTrade trade = new FuturesTrade();
		trade.symbol = position.symbol;
		trade.strategyCode = position.signal.strategyCode;
		trade.strategyName = position.signal.strategyName;
		trade.side = position.side;
		trade.contracts = position.contracts;
		trade.entryPrice = roundToTick(position.spec, position.entryPrice);
		trade.exitPrice = roundToTick(position.spec, exitPrice);
		trade.stopPrice = roundToTick(position.spec, position.stopPrice);
		trade.targetPrice = roundToTick(position.spec, position.targetPrice);
		trade.openedAt = position.openedAt;
		trade.closedAt = bar.displayTime;
		trade.pnl = round(grossPnl - commissions);
		trade.mfe = round(position.maxFavorable);
		trade.mae = round(position.maxAdverse);
		trade.exitReason = exitReason;
		trade.entryIndex = position.entryIndex;
		trade.exitIndex = exitIndex;
		trade.openedMarketTime = position.openedMarketTime;
		trade.closedMarketTime = bar.marketTime;
		trade.notes = position.signal.notes
			+ " Portfolio event-driven entry; concurrent positions " + position.concurrentPositionsAtEntry
			+ ", concurrent contracts " + position.concurrentContractsAtEntry
			+ ", entered next bar open"
			+ ", risk/contract $" + round(position.riskPerContract)
			+ ", MAE sizing multiplier " + round(context.config.strategySettings.openMaeRiskMultiplier)
			+ ", commission $" + round(commissions)
			+ ", MFE $" + trade.mfe
			+ ", MAE $" + trade.mae
			+ ".";
		return trade;
	}

	private static BacktestResult runBacktest(BacktestConfig config) {
		InstrumentSpec spec = instrumentFor(config.symbol);
		DataBundle bundle = loadBars(config.symbol, config.startDate, config.endDate);
		if (bundle.bars.isEmpty()) {
			return null;
		}
		DataBundle fifteenMinuteBundle = loadNativeFuturesBars(config.symbol, config.startDate, config.endDate, "15min");
		DataBundle oneHourBundle = loadNativeFuturesBars(config.symbol, config.startDate, config.endDate, "1hour");

		Map<LocalDate, List<Bar>> byDay = groupByDay(bundle.bars);
		Map<LocalDate, List<Bar>> fifteenMinuteByDay = groupByDay(fifteenMinuteBundle.bars);
		Map<LocalDate, List<Bar>> oneHourByDay = groupByDay(oneHourBundle.bars);
		List<LocalDate> days = new ArrayList<LocalDate>(byDay.keySet());
		Collections.sort(days);

		BacktestResult result = new BacktestResult();
		result.symbol = spec.symbol;
		result.contractName = spec.name;
		result.startDate = days.isEmpty() ? config.startDate.toString() : days.get(0).toString();
		result.endDate = days.isEmpty() ? config.endDate.toString() : days.get(days.size() - 1).toString();
		result.startingBalance = round(config.accountSize);
		result.dataSource = bundle.source;

		double balance = config.accountSize;
		double peakBalance = balance;
		double trailingReferenceBalance = balance;
		double trailingThreshold = config.accountSize - config.maxTrailingDrawdown;
		double grossProfit = 0.0;
		double grossLoss = 0.0;
		int winners = 0;
		double maxDrawdownPct = 0.0;
		boolean endOfDayTrailing = usesEndOfDayTrailing(config.trailingDrawdownMode);

		for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
			LocalDate day = days.get(dayIndex);
			List<Bar> bars = byDay.get(day);
			if (bars == null || bars.size() < 40) {
				continue;
			}

			double dayStartBalance = balance;
			List<Signal> signals = buildSignals(
				spec,
				bars,
				previousDayBars(byDay, days, dayIndex),
				fifteenMinuteByDay.get(day),
				oneHourByDay.get(day),
				config
			);
			Collections.sort(signals, new Comparator<Signal>() {
				@Override
				public int compare(Signal first, Signal second) {
					return bars.get(first.entryIndex).marketTime.compareTo(bars.get(second.entryIndex).marketTime);
				}
			});

			Map<String, Integer> takenByStrategy = new HashMap<String, Integer>();
			int flatAfterIndex = -1;
			for (int signalIndex = 0; signalIndex < signals.size(); signalIndex++) {
				Signal signal = signals.get(signalIndex);
				if (signal.entryIndex <= flatAfterIndex) {
					continue;
				}
				if (balance - dayStartBalance <= -Math.abs(config.dailyLossLimit)) {
					break;
				}
				if (balance <= trailingThreshold) {
					result.ruleViolation = true;
					result.ruleMessage = "Trailing drawdown threshold breached.";
					break;
				}
				if (config.profitTarget > 0.0 && balance - config.accountSize >= config.profitTarget) {
					result.ruleMessage = "Profit target reached.";
					break;
				}

				int taken = countFor(takenByStrategy, signal.strategyCode);
				if (taken >= maxTradesPerDay(signal.strategyCode, config.strategySettings)) {
					continue;
				}
				double realizedDayPnl = balance - dayStartBalance;
				double dailyRiskBudget = Math.abs(config.dailyLossLimit) + realizedDayPnl;
				double trailingRiskBudget = balance - trailingThreshold;
				double availableRiskBudget = Math.min(config.maxRiskPerTrade, Math.min(dailyRiskBudget, trailingRiskBudget));
				if (availableRiskBudget <= 0.0) {
					continue;
				}
				FuturesTrade trade = simulateTrade(spec, bars, signal, config, availableRiskBudget, Math.min(dailyRiskBudget, trailingRiskBudget));
				if (trade == null) {
					continue;
				}
				double entryCost = (config.commissionPerContract * trade.contracts) + (config.slippageTicks * spec.tickValue * trade.contracts);
				double worstOpenPnl = trade.mae - entryCost;
				if ((balance - dayStartBalance) + worstOpenPnl <= -Math.abs(config.dailyLossLimit)) {
					result.ruleViolation = true;
					result.ruleMessage = "Intraday daily loss limit breached before exit.";
				}
				if (balance + worstOpenPnl <= trailingThreshold) {
					result.ruleViolation = true;
					result.ruleMessage = "Trailing drawdown threshold breached intratrade.";
				}

				balance = round(balance + trade.pnl);
				if (trade.pnl >= 0.0) {
					winners++;
					grossProfit = round(grossProfit + trade.pnl);
				} else {
					grossLoss = round(grossLoss + Math.abs(trade.pnl));
				}
				peakBalance = Math.max(peakBalance, balance);
				if (!endOfDayTrailing) {
					trailingThreshold = Math.max(trailingThreshold, peakBalance - config.maxTrailingDrawdown);
				}
				double drawdownPct = peakBalance <= 0.0 ? 0.0 : ((peakBalance - balance) / peakBalance) * 100.0;
				maxDrawdownPct = Math.max(maxDrawdownPct, drawdownPct);
				result.tradeRecords.add(trade);
				takenByStrategy.put(signal.strategyCode, taken + 1);
				flatAfterIndex = Math.max(flatAfterIndex, trade.exitIndex);
			}

			if (result.ruleViolation || "Profit target reached.".equals(result.ruleMessage)) {
				break;
			}
			if (endOfDayTrailing) {
				trailingReferenceBalance = Math.max(trailingReferenceBalance, balance);
				trailingThreshold = endOfDayTrailingThreshold(trailingThreshold, trailingReferenceBalance, config.accountSize, config.maxTrailingDrawdown);
			}
		}

		result.endingBalance = round(balance);
		result.totalProfit = round(balance - config.accountSize);
		result.returnPct = config.accountSize <= 0.0 ? 0.0 : round((result.totalProfit / config.accountSize) * 100.0);
		result.trades = result.tradeRecords.size();
		result.winRate = result.trades == 0 ? 0.0 : round((winners * 100.0) / result.trades);
		result.profitFactor = grossLoss == 0.0 ? round(grossProfit) : round(grossProfit / grossLoss);
		result.maxDrawdownPct = round(maxDrawdownPct);
		result.trailingThreshold = round(trailingThreshold);
		if (result.ruleMessage == null || result.ruleMessage.trim().isEmpty()) {
			result.ruleMessage = result.ruleViolation ? "Funded rule violation." : "Completed without funded rule breach.";
		}
		return result;
	}

	private static FuturesTrade simulateTrade(InstrumentSpec spec, List<Bar> bars, Signal signal, BacktestConfig config, double riskBudget, double aggregateGuardBudget) {
		int executionIndex = signal.entryIndex + 1;
		if (executionIndex >= bars.size()) {
			return null;
		}

		Bar entryBar = bars.get(executionIndex);
		if (!entryBar.marketTime.isBefore(FORCED_EXIT_TIME)) {
			return null;
		}

		double signalRisk = Math.abs(signal.entryPrice - signal.stopPrice);
		if (signalRisk <= 0.0) {
			return null;
		}

		double rewardMultiple = Math.abs(signal.targetPrice - signal.entryPrice) / signalRisk;
		if (rewardMultiple <= 0.0 || Double.isNaN(rewardMultiple) || Double.isInfinite(rewardMultiple)) {
			rewardMultiple = spec.defaultTargetR;
		}

		double entryPrice = applySlippage(spec, entryBar.open, signal.side, config.slippageTicks, true);
		double stopPrice = roundToTick(spec, signal.stopPrice);
		if ("LONG".equals(signal.side) && entryPrice <= stopPrice) {
			return null;
		}
		if ("SHORT".equals(signal.side) && entryPrice >= stopPrice) {
			return null;
		}

		double initialRisk = Math.abs(entryPrice - stopPrice);
		double rawRiskTicks = initialRisk / spec.tickSize;
		if (rawRiskTicks < 1.0) {
			return null;
		}

		FuturesStrategySettings settings = config.strategySettings == null ? defaultFuturesStrategySettings() : config.strategySettings;
		if (rawRiskTicks > settings.maxInitialRiskTicks) {
			return null;
		}
		rewardMultiple = adaptiveRewardMultiple(spec, bars, signal, executionIndex, rewardMultiple, settings);

		double stopFillForRisk = applySlippage(spec, stopPrice, signal.side, config.slippageTicks, false);
		double riskPerContract = (Math.abs(entryPrice - stopFillForRisk) / spec.tickSize * spec.tickValue) + (config.commissionPerContract * 2.0);
		double sizingRiskPerContract = riskPerContract * settings.openMaeRiskMultiplier;
		double effectiveRiskBudget = Math.max(0.0, Math.min(config.maxRiskPerTrade, riskBudget));
		int contracts = Math.min(config.maxContracts, (int) Math.floor(effectiveRiskBudget / Math.max(1.0, riskPerContract)));
		while (contracts > 0 && (sizingRiskPerContract * contracts) > Math.max(0.0, aggregateGuardBudget)) {
			contracts--;
		}
		if (contracts < 1) {
			return null;
		}

		double targetPrice = "LONG".equals(signal.side)
			? roundToTick(spec, entryPrice + (initialRisk * rewardMultiple))
			: roundToTick(spec, entryPrice - (initialRisk * rewardMultiple));
		double activeStopPrice = stopPrice;
		double minTrailDistance = Math.max(spec.tickSize * 8.0, initialRisk * 0.55);
		int exitIndex = bars.size() - 1;
		double exitPrice = bars.get(exitIndex).close;
		String exitReason = "Session close exit";
		double maxFavorable = 0.0;
		double maxAdverse = 0.0;

		for (int index = executionIndex; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			double favorable = favorablePnl(spec, signal.side, entryPrice, bar, contracts);
			double adverse = adversePnl(spec, signal.side, entryPrice, bar, contracts);
			maxFavorable = Math.max(maxFavorable, favorable);
			maxAdverse = Math.min(maxAdverse, adverse);

			if ("LONG".equals(signal.side)) {
				if (bar.low <= activeStopPrice && bar.high >= targetPrice) {
					exitPrice = activeStopPrice;
					exitIndex = index;
					exitReason = "Stop and target touched; stop assumed first";
					break;
				}
				if (bar.low <= activeStopPrice) {
					exitPrice = activeStopPrice;
					exitIndex = index;
					exitReason = activeStopPrice == stopPrice ? "Stop loss hit" : "Managed stop hit";
					break;
				}
				if (bar.high >= targetPrice) {
					exitPrice = targetPrice;
					exitIndex = index;
					exitReason = "Target reached";
					break;
				}
			} else {
				if (bar.high >= activeStopPrice && bar.low <= targetPrice) {
					exitPrice = activeStopPrice;
					exitIndex = index;
					exitReason = "Stop and target touched; stop assumed first";
					break;
				}
				if (bar.high >= activeStopPrice) {
					exitPrice = activeStopPrice;
					exitIndex = index;
					exitReason = activeStopPrice == stopPrice ? "Stop loss hit" : "Managed stop hit";
					break;
				}
				if (bar.low <= targetPrice) {
					exitPrice = targetPrice;
					exitIndex = index;
					exitReason = "Target reached";
					break;
				}
			}

			double guardCostBuffer = (config.commissionPerContract * contracts * 2.0) + (config.slippageTicks * spec.tickValue * contracts);
			if (aggregateGuardBudget > 0.0 && adverse - guardCostBuffer <= -Math.abs(aggregateGuardBudget)) {
				exitPrice = "LONG".equals(signal.side) ? bar.low : bar.high;
				exitIndex = index;
				exitReason = "Funded guard breach flat exit";
				break;
			}
			if (shouldEarlyLossCut(spec, settings, signal.side, entryPrice, initialRisk, bar, contracts, index - executionIndex)) {
				exitPrice = bar.close;
				exitIndex = index;
				exitReason = "Adaptive loss cut exit";
				break;
			}
			if (!bar.marketTime.isBefore(FORCED_EXIT_TIME)) {
				exitPrice = bar.close;
				exitIndex = index;
				exitReason = "Funded-session flat exit";
				break;
			}
			if (signal.maxHoldBars > 0 && index - executionIndex >= signal.maxHoldBars) {
				exitPrice = bar.close;
				exitIndex = index;
				exitReason = "Time stop exit";
				break;
			}
			activeStopPrice = updateManagedStop(spec, signal.side, entryPrice, activeStopPrice, bar.close, initialRisk, minTrailDistance);
		}

		exitPrice = applySlippage(spec, exitPrice, signal.side, config.slippageTicks, false);
		double grossPnl = pnlForPrice(spec, signal.side, entryPrice, exitPrice, contracts);
		double commissions = config.commissionPerContract * contracts * 2.0;

		FuturesTrade trade = new FuturesTrade();
		trade.symbol = spec.symbol;
		trade.strategyCode = signal.strategyCode;
		trade.strategyName = signal.strategyName;
		trade.side = signal.side;
		trade.contracts = contracts;
		trade.entryPrice = roundToTick(spec, entryPrice);
		trade.exitPrice = roundToTick(spec, exitPrice);
		trade.stopPrice = roundToTick(spec, stopPrice);
		trade.targetPrice = roundToTick(spec, targetPrice);
		trade.openedAt = bars.get(executionIndex).displayTime;
		trade.closedAt = bars.get(exitIndex).displayTime;
		trade.pnl = round(grossPnl - commissions);
		trade.mfe = round(maxFavorable);
		trade.mae = round(maxAdverse);
		trade.exitReason = exitReason;
		trade.entryIndex = executionIndex;
		trade.exitIndex = exitIndex;
		trade.openedMarketTime = bars.get(executionIndex).marketTime;
		trade.closedMarketTime = bars.get(exitIndex).marketTime;
		trade.notes = signal.notes
			+ " Contracts " + contracts
			+ ", signal bar " + bars.get(signal.entryIndex).displayTime
			+ ", entered next bar open"
			+ ", risk/contract $" + round(riskPerContract)
			+ ", MAE sizing risk/contract $" + round(sizingRiskPerContract)
			+ ", risk budget $" + round(effectiveRiskBudget)
			+ ", commission $" + round(commissions)
			+ ", MFE $" + trade.mfe
			+ ", MAE $" + trade.mae
			+ ".";
		return trade;
	}

	private static List<Signal> buildSignals(
		InstrumentSpec spec,
		List<Bar> bars,
		List<Bar> previousBars,
		List<Bar> fifteenMinuteBars,
		List<Bar> oneHourBars,
		BacktestConfig config
	) {
		List<Signal> signals = new ArrayList<Signal>();
		FuturesStrategySettings settings = config.strategySettings == null ? defaultFuturesStrategySettings() : config.strategySettings;
			if (settings.orb.enabled) {
				Signal signal = findOrbSignal(spec, bars, fifteenMinuteBars, oneHourBars, settings);
			if (signal != null) {
				signals.add(signal);
			}
			if (settings.enableOrbRetest) {
				signals.addAll(findOrbRetestSignals(spec, bars, settings));
			}
		}
		if (settings.openingMomentum.enabled) {
			signals.addAll(findOpeningMomentumSignals(spec, bars, settings));
		}
		if (settings.vwapPullback.enabled) {
			signals.addAll(findVwapPullbackSignals(spec, bars, settings));
		}
		if (settings.afternoonContinuation.enabled) {
			signals.addAll(findAfternoonContinuationSignals(spec, bars, fifteenMinuteBars, oneHourBars, settings));
		}
		if (settings.marketIntradayMomentum.enabled) {
			signals.addAll(findMarketIntradayMomentumSignals(spec, bars, fifteenMinuteBars, oneHourBars, settings));
		}
		if (settings.keltnerScalp.enabled) {
			signals.addAll(findKeltnerScalpSignals(spec, bars, fifteenMinuteBars, oneHourBars, settings));
		}
		if (settings.keltnerReversion.enabled) {
			signals.addAll(findKeltnerReversionSignals(spec, bars, fifteenMinuteBars, oneHourBars, settings));
		}
		if (settings.microScalp.enabled) {
			signals.addAll(findMicroScalpSignals(spec, bars, settings));
		}
		if (settings.vwapMeanReversion.enabled) {
			signals.addAll(findMeanReversionSignals(spec, bars, settings));
		}
		if (settings.sweep.enabled) {
			signals.addAll(findSweepSignals(spec, bars, previousBars, fifteenMinuteBars, oneHourBars, settings));
		}
		if (settings.fvg.enabled) {
			signals.addAll(findFvgSignals(spec, bars));
		}
		if (settings.closeMomentum.enabled) {
			signals.addAll(findCloseMomentumSignals(spec, bars, fifteenMinuteBars, oneHourBars, settings));
		}
		return signals;
	}

	private static Signal findOrbSignal(InstrumentSpec spec, List<Bar> bars, List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, FuturesStrategySettings settings) {
		double high = Double.NEGATIVE_INFINITY;
		double low = Double.POSITIVE_INFINITY;
		double volume = 0.0;
		int openingBars = 0;
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (!bar.marketTime.isBefore(RTH_START) && bar.marketTime.isBefore(ORB_END)) {
				high = Math.max(high, bar.high);
				low = Math.min(low, bar.low);
				volume += bar.volume;
				openingBars++;
			}
		}
		if (openingBars < 5 || high <= low) {
			return null;
		}
		double averageVolume = volume / openingBars;
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (bar.marketTime.isBefore(ORB_END)) {
				continue;
			}
			if (bar.marketTime.isAfter(ORB_CUTOFF)) {
				break;
			}
			if (bar.volume < averageVolume * 0.85) {
				continue;
			}
			double closeLocation = closeLocation(bar);
			if (
				bar.close > high + (spec.tickSize * 2.0)
				&& bar.close > bar.open
				&& closeLocation >= ORB_LONG_MIN_CLOSE_LOCATION
				&& closeLocation <= ORB_LONG_MAX_CLOSE_LOCATION
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeBreakoutLong(fifteenMinuteBars, oneHourBars, bar.marketTime))
			) {
				double stop = settings.enableCompressedOrbBreakout
					? compressedLongStop(spec, bars, index, bar.close, Math.min(settings.orbCompressedMaxRiskTicks, settings.maxInitialRiskTicks))
					: low - (spec.tickSize * 2.0);
				double risk = bar.close - stop;
				if (risk <= 0.0) {
					continue;
				}
				return signal("ORB", "Opening Range Breakout", "LONG", index, bar.close, stop, bar.close + (risk * 1.2), "Futures ORB long above RTH opening range with body-location and higher-timeframe filters.");
			}
			if (bar.marketTime.isBefore(ORB_SHORT_CONFIRMATION_TIME) || !settings.allowShorts) {
				continue;
			}
			if (bar.close < low - (spec.tickSize * 2.0) && bar.close < bar.open) {
				double stop = settings.enableCompressedOrbBreakout
					? compressedShortStop(spec, bars, index, bar.close, Math.min(settings.orbCompressedMaxRiskTicks, settings.maxInitialRiskTicks))
					: high + (spec.tickSize * 2.0);
				double risk = stop - bar.close;
				if (risk <= 0.0) {
					continue;
				}
				return signal("ORB", "Opening Range Breakout", "SHORT", index, bar.close, stop, bar.close - (risk * 1.2), "Futures ORB short below RTH opening range after confirmation window.");
			}
		}
		return null;
	}

	private static List<Signal> findOrbRetestSignals(InstrumentSpec spec, List<Bar> bars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		double high = Double.NEGATIVE_INFINITY;
		double low = Double.POSITIVE_INFINITY;
		double volume = 0.0;
		int openingBars = 0;
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (!bar.marketTime.isBefore(RTH_START) && bar.marketTime.isBefore(ORB_END)) {
				high = Math.max(high, bar.high);
				low = Math.min(low, bar.low);
				volume += bar.volume;
				openingBars++;
			}
		}
		if (openingBars < 5 || high <= low) {
			return signals;
		}
		double averageVolume = volume / openingBars;
		double maxOrbRetestRiskTicks = Math.min(settings.maxInitialRiskTicks, settings.orbRetestMaxRiskTicks);
		boolean brokeLong = false;
		boolean brokeShort = false;
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (bar.marketTime.isBefore(ORB_END)) {
				continue;
			}
			if (bar.marketTime.isAfter(LocalTime.of(11, 45))) {
				break;
			}
			int minutesAfterOpen = (bar.marketTime.getHour() * 60 + bar.marketTime.getMinute()) - (RTH_START.getHour() * 60 + RTH_START.getMinute());
			if (minutesAfterOpen < settings.orbRetestStartMinutes || minutesAfterOpen > settings.orbRetestEndMinutes) {
				continue;
			}
			if (settings.skipMidmorningOrbRetest && !bar.marketTime.isBefore(LocalTime.of(10, 0)) && bar.marketTime.isBefore(LocalTime.of(11, 30))) {
				continue;
			}
			if (bar.close > high + (spec.tickSize * 2.0)) {
				brokeLong = true;
			}
			if (settings.allowShorts && bar.close < low - (spec.tickSize * 2.0)) {
				brokeShort = true;
			}
			if (settings.allowOrbRetestLongs && brokeLong && bar.low <= high + (spec.tickSize * 2.0) && bar.close > high && bar.close > bar.open && closeLocation(bar) >= 0.58 && bar.volume >= averageVolume * 0.65) {
				double stop = recentSwingLow(bars, index, 3) - (spec.tickSize * 2.0);
				double risk = bar.close - stop;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxOrbRetestRiskTicks) {
					signals.add(signal("ORB2", "Opening Range Retest", "LONG", index, bar.close, stop, bar.close + (risk * Math.max(settings.minRewardRisk, 1.0)), settings.openingMomentumMaxHoldBars, "ORB long retest after breakout holds opening range high with compressed swing risk."));
					break;
				}
			}
			if (settings.allowOrbRetestShorts && brokeShort && bar.high >= low - (spec.tickSize * 2.0) && bar.close < low && bar.close < bar.open && closeLocation(bar) <= 0.42 && bar.volume >= averageVolume * 0.65) {
				double stop = recentSwingHigh(bars, index, 3) + (spec.tickSize * 2.0);
				double risk = stop - bar.close;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxOrbRetestRiskTicks) {
					signals.add(signal("ORB2", "Opening Range Retest", "SHORT", index, bar.close, stop, bar.close - (risk * Math.max(settings.minRewardRisk, 1.0)), settings.openingMomentumMaxHoldBars, "ORB short retest after breakdown holds opening range low with compressed swing risk."));
					break;
				}
			}
		}
		return signals;
	}

	private static List<Signal> findOpeningMomentumSignals(InstrumentSpec spec, List<Bar> bars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		LocalTime rangeEnd = RTH_START.plusMinutes(settings.openingMomentumRangeMinutes);
		double high = Double.NEGATIVE_INFINITY;
		double low = Double.POSITIVE_INFINITY;
		double volume = 0.0;
		int openingBars = 0;
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (!bar.marketTime.isBefore(RTH_START) && bar.marketTime.isBefore(rangeEnd)) {
				high = Math.max(high, bar.high);
				low = Math.min(low, bar.low);
				volume += bar.volume;
				openingBars++;
			}
		}
		if (openingBars < Math.max(5, settings.openingMomentumRangeMinutes / 2) || high <= low) {
			return signals;
		}

		double averageVolume = volume / openingBars;
		boolean longTaken = false;
		boolean shortTaken = false;
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (bar.marketTime.isBefore(rangeEnd)) {
				continue;
			}
			if (bar.marketTime.isAfter(OPENING_MOMENTUM_END)) {
				break;
			}
			if (bar.volume < averageVolume * settings.openingMomentumVolumeRatio) {
				continue;
			}
			double closeLocation = closeLocation(bar);
			if (!longTaken
				&& bar.close > high + (spec.tickSize * 2.0)
				&& bar.close > bar.open
				&& closeLocation >= 0.55
				&& closeLocation <= 0.78) {
				double stop = recentSwingLow(bars, index, 3) - (spec.tickSize * 2.0);
				double risk = bar.close - stop;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= settings.maxInitialRiskTicks) {
					signals.add(signal("OMOM", "Compressed Opening Momentum", "LONG", index, bar.close, stop, bar.close + (risk * settings.openingMomentumRewardRisk), settings.openingMomentumMaxHoldBars, settings.openingMomentumRangeMinutes + "m compressed opening range long breakout with swing-risk stop."));
					longTaken = true;
				}
			}
			if (settings.allowShorts
				&& !shortTaken
				&& !bar.marketTime.isBefore(ORB_SHORT_CONFIRMATION_TIME)
				&& bar.close < low - (spec.tickSize * 2.0)
				&& bar.close < bar.open
				&& (1.0 - closeLocation) >= 0.55) {
				double stop = recentSwingHigh(bars, index, 3) + (spec.tickSize * 2.0);
				double risk = stop - bar.close;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= settings.maxInitialRiskTicks) {
					signals.add(signal("OMOM", "Compressed Opening Momentum", "SHORT", index, bar.close, stop, bar.close - (risk * settings.openingMomentumRewardRisk), settings.openingMomentumMaxHoldBars, settings.openingMomentumRangeMinutes + "m compressed opening range short breakdown with swing-risk stop."));
					shortTaken = true;
				}
			}
			if (signals.size() >= settings.openingMomentum.maxTradesPerDay) {
				break;
			}
		}
		return dedupeByHour(signals, settings.openingMomentum.maxTradesPerDay);
	}

	private static List<Signal> findVwapPullbackSignals(InstrumentSpec spec, List<Bar> bars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		for (int index = 22; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			Bar previous = bars.get(index - 1);
			if (bar.marketTime.isBefore(VWAP_START) || bar.marketTime.isAfter(VWAP_END) || bar.vwap <= 0.0) {
				continue;
			}
			double vwapDistanceTicks = Math.abs(bar.close - bar.vwap) / spec.tickSize;
			double volumeRatio = bar.volumeSma20 <= 0.0 ? 1.0 : bar.volume / bar.volumeSma20;
			double trendSlopeTicks = Math.abs(bar.ema20 - bars.get(Math.max(0, index - 5)).ema20) / spec.tickSize;
			if (vwapDistanceTicks > settings.vwapMaxDistanceTicks || volumeRatio < settings.vwapMinVolumeRatio || trendSlopeTicks < settings.vwapMinTrendSlopeTicks) {
				continue;
			}
			double maxVwapRiskTicks = Math.min(settings.maxInitialRiskTicks, settings.vwapMaxRiskTicks);
			double closeLocation = closeLocation(bar);
			if (bar.close > bar.vwap
				&& bar.ema9 > bar.ema20
				&& bar.ema20 >= bar.ema50
				&& previous.low <= previous.ema20 + spec.tickSize
				&& bar.close > previous.high
				&& closeLocation >= 0.55) {
				double stop = Math.min(recentSwingLow(bars, index, 4), bar.ema20) - (spec.tickSize * 2.0);
				double risk = bar.close - stop;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxVwapRiskTicks) {
					signals.add(signal("VWAP", "VWAP Trend Pullback", "LONG", index, bar.close, stop, bar.close + (risk * Math.max(settings.minRewardRisk, 1.1)), 15, "Compressed VWAP/EMA trend-continuation long after pullback holds above VWAP."));
				}
			}
			if (settings.allowShorts
				&& bar.close < bar.vwap
				&& bar.ema9 < bar.ema20
				&& bar.ema20 <= bar.ema50
				&& previous.high >= previous.ema20 - spec.tickSize
				&& bar.close < previous.low
				&& closeLocation <= 0.45) {
				double stop = Math.max(recentSwingHigh(bars, index, 4), bar.ema20) + (spec.tickSize * 2.0);
				double risk = stop - bar.close;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxVwapRiskTicks) {
					signals.add(signal("VWAP", "VWAP Trend Pullback", "SHORT", index, bar.close, stop, bar.close - (risk * Math.max(settings.minRewardRisk, 1.1)), 15, "Compressed VWAP/EMA trend-continuation short after pullback fails below VWAP."));
				}
			}
		}
		return dedupeByHour(signals, settings.vwapPullback.maxTradesPerDay);
	}

	private static List<Signal> findMicroScalpSignals(InstrumentSpec spec, List<Bar> bars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		if (bars == null || bars.size() < 80) {
			return signals;
		}
		int lastBucket = -1;
		double maxRiskTicks = Math.min(settings.maxInitialRiskTicks, settings.microScalpMaxRiskTicks);
		for (int index = 30; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			Bar previous = bars.get(index - 1);
			if (bar.marketTime.isBefore(LocalTime.of(9, 50)) || bar.marketTime.isAfter(LocalTime.of(15, 20)) || bar.vwap <= 0.0) {
				continue;
			}
			int bucket = ((bar.marketTime.getHour() * 60) + bar.marketTime.getMinute()) / Math.max(5, settings.microScalpBucketMinutes);
			if (bucket == lastBucket) {
				continue;
			}
			double volumeRatio = bar.volumeSma20 <= 0.0 ? 1.0 : bar.volume / bar.volumeSma20;
			if (volumeRatio < settings.microScalpMinVolumeRatio || bar.bodyPct < settings.microScalpMinBodyPct) {
				continue;
			}
			double trendSlopeTicks = (bar.ema20 - bars.get(Math.max(0, index - 8)).ema20) / spec.tickSize;
			double closeLocation = closeLocation(bar);
			if (bar.close > bar.vwap
				&& bar.ema9 >= bar.ema20
				&& bar.ema20 >= bar.ema50
				&& trendSlopeTicks >= settings.microScalpMinTrendSlopeTicks
				&& previous.low <= Math.max(previous.ema9, previous.ema20) + (spec.tickSize * 2.0)
				&& bar.close > previous.high
				&& closeLocation >= 0.56) {
				double stop = Math.min(recentSwingLow(bars, index, 3), bar.ema20) - (spec.tickSize * 2.0);
				double risk = bar.close - stop;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("MSCALP", "Micro Trend Scalp", "LONG", index, bar.close, stop, bar.close + (risk * settings.microScalpRewardRisk), settings.microScalpMaxHoldBars, "Causal 1-minute futures scalp after VWAP/EMA trend pullback and next-bar execution."));
					lastBucket = bucket;
				}
			}
			if (settings.allowShorts
				&& bar.close < bar.vwap
				&& bar.ema9 <= bar.ema20
				&& bar.ema20 <= bar.ema50
				&& trendSlopeTicks <= -settings.microScalpMinTrendSlopeTicks
				&& previous.high >= Math.min(previous.ema9, previous.ema20) - (spec.tickSize * 2.0)
				&& bar.close < previous.low
				&& closeLocation <= 0.44) {
				double stop = Math.max(recentSwingHigh(bars, index, 3), bar.ema20) + (spec.tickSize * 2.0);
				double risk = stop - bar.close;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("MSCALP", "Micro Trend Scalp", "SHORT", index, bar.close, stop, bar.close - (risk * settings.microScalpRewardRisk), settings.microScalpMaxHoldBars, "Causal 1-minute futures scalp after VWAP/EMA trend pullback and next-bar execution."));
					lastBucket = bucket;
				}
			}
			if (signals.size() >= settings.microScalp.maxTradesPerDay) {
				break;
			}
		}
		return signals;
	}

	private static List<Signal> findKeltnerScalpSignals(InstrumentSpec spec, List<Bar> bars, List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		if (bars == null || bars.size() < 80) {
			return signals;
		}
		int lastBucket = -1;
		double maxRiskTicks = Math.min(settings.maxInitialRiskTicks, settings.keltnerMaxRiskTicks);
		double atrMultiplier = Math.max(0.7, settings.keltnerAtrMultiplier);
		int bucketMinutes = Math.max(3, settings.keltnerBucketMinutes);
		for (int index = 55; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			Bar previous = bars.get(index - 1);
			if (bar.marketTime.isBefore(KELTNER_SCALP_START) || bar.marketTime.isAfter(KELTNER_SCALP_END) || bar.vwap <= 0.0 || bar.atr14 <= 0.0 || bar.ema20 <= 0.0) {
				continue;
			}
			int bucket = ((bar.marketTime.getHour() * 60) + bar.marketTime.getMinute()) / bucketMinutes;
			if (bucket == lastBucket) {
				continue;
			}
			double upperBand = bar.ema20 + (bar.atr14 * atrMultiplier);
			double lowerBand = bar.ema20 - (bar.atr14 * atrMultiplier);
			double bandWidthTicks = (upperBand - lowerBand) / spec.tickSize;
			if (bandWidthTicks < settings.keltnerMinBandWidthTicks) {
				continue;
			}
			double volumeRatio = bar.volumeSma20 <= 0.0 ? 1.0 : bar.volume / bar.volumeSma20;
			if (volumeRatio < settings.keltnerMinVolumeRatio || bar.bodyPct < settings.keltnerMinBodyPct) {
				continue;
			}
			double trendSlopeTicks = (bar.ema20 - bars.get(Math.max(0, index - 10)).ema20) / spec.tickSize;
			double closeLocation = closeLocation(bar);
			double previousUpperBand = previous.ema20 + (Math.max(0.0, previous.atr14) * atrMultiplier);
			double previousLowerBand = previous.ema20 - (Math.max(0.0, previous.atr14) * atrMultiplier);
			boolean longMomentum = previous.close > previousUpperBand || bar.close > previous.high + spec.tickSize;
			boolean shortMomentum = previous.close < previousLowerBand || bar.close < previous.low - spec.tickSize;

			if (settings.allowKeltnerScalpLongs
				&& bar.close > upperBand
				&& longMomentum
				&& bar.close > bar.vwap
				&& bar.ema9 >= bar.ema20
				&& bar.ema20 >= bar.ema50
				&& trendSlopeTicks >= settings.keltnerMinTrendSlopeTicks
				&& bar.rsi14 >= 50.0
				&& closeLocation >= 0.56
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeBreakoutLong(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
				double stop = compressedLongStop(spec, bars, index, bar.close, maxRiskTicks);
				double risk = bar.close - stop;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("KELT", "Keltner ATR Breakout Scalp", "LONG", index, bar.close, stop, bar.close + (risk * settings.keltnerRewardRisk), settings.keltnerMaxHoldBars, "1-minute Keltner/ATR volatility breakout scalp with VWAP, EMA, RSI, volume, and next-bar execution filters."));
					lastBucket = bucket;
				}
			}
			if (settings.allowShorts
				&& settings.allowKeltnerScalpShorts
				&& bar.close < lowerBand
				&& shortMomentum
				&& bar.close < bar.vwap
				&& bar.ema9 <= bar.ema20
				&& bar.ema20 <= bar.ema50
				&& trendSlopeTicks <= -settings.keltnerMinTrendSlopeTicks
				&& bar.rsi14 <= 50.0
				&& closeLocation <= 0.44
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeBearish(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
				double stop = compressedShortStop(spec, bars, index, bar.close, maxRiskTicks);
				double risk = stop - bar.close;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("KELT", "Keltner ATR Breakout Scalp", "SHORT", index, bar.close, stop, bar.close - (risk * settings.keltnerRewardRisk), settings.keltnerMaxHoldBars, "1-minute Keltner/ATR volatility breakout scalp with VWAP, EMA, RSI, volume, and next-bar execution filters."));
					lastBucket = bucket;
				}
			}
			if (signals.size() >= settings.keltnerScalp.maxTradesPerDay) {
				break;
			}
		}
		return signals;
	}

	private static List<Signal> findKeltnerReversionSignals(InstrumentSpec spec, List<Bar> bars, List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		if (bars == null || bars.size() < 80) {
			return signals;
		}
		int lastBucket = -1;
		double maxRiskTicks = Math.min(settings.maxInitialRiskTicks, settings.keltnerMaxRiskTicks);
		double atrMultiplier = Math.max(0.7, settings.keltnerAtrMultiplier);
		double maxTrendSlopeTicks = Math.max(3.0, settings.keltnerMinTrendSlopeTicks * 8.0);
		int bucketMinutes = Math.max(3, settings.keltnerBucketMinutes);
		for (int index = 55; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			Bar previous = bars.get(index - 1);
			if (bar.marketTime.isBefore(LocalTime.of(10, 0)) || bar.marketTime.isAfter(KELTNER_SCALP_END) || bar.vwap <= 0.0 || bar.atr14 <= 0.0 || bar.ema20 <= 0.0) {
				continue;
			}
			int bucket = ((bar.marketTime.getHour() * 60) + bar.marketTime.getMinute()) / bucketMinutes;
			if (bucket == lastBucket) {
				continue;
			}
			double upperBand = bar.ema20 + (bar.atr14 * atrMultiplier);
			double lowerBand = bar.ema20 - (bar.atr14 * atrMultiplier);
			double previousUpperBand = previous.ema20 + (Math.max(0.0, previous.atr14) * atrMultiplier);
			double previousLowerBand = previous.ema20 - (Math.max(0.0, previous.atr14) * atrMultiplier);
			double bandWidthTicks = (upperBand - lowerBand) / spec.tickSize;
			double distanceToVwapTicks = Math.abs(bar.close - bar.vwap) / spec.tickSize;
			if (bandWidthTicks < settings.keltnerMinBandWidthTicks || distanceToVwapTicks > settings.vwapMaxDistanceTicks) {
				continue;
			}
			double volumeRatio = bar.volumeSma20 <= 0.0 ? 1.0 : bar.volume / bar.volumeSma20;
			if (volumeRatio < settings.keltnerMinVolumeRatio || bar.bodyPct < settings.keltnerMinBodyPct) {
				continue;
			}
			double trendSlopeTicks = (bar.ema20 - bars.get(Math.max(0, index - 10)).ema20) / spec.tickSize;
			if (Math.abs(trendSlopeTicks) > maxTrendSlopeTicks) {
				continue;
			}
			double closeLocation = closeLocation(bar);

			if (settings.allowKeltnerScalpLongs
				&& previous.close < previousLowerBand
				&& bar.close > lowerBand
				&& bar.close > previous.high
				&& bar.rsi14 <= 48.0
				&& closeLocation >= 0.56
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeConstructive(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
				double stop = compressedLongStop(spec, bars, index, bar.close, maxRiskTicks);
				double risk = bar.close - stop;
				double target = Math.min(bar.ema20, bar.close + (risk * settings.keltnerRewardRisk));
				if (risk > 0.0 && target > bar.close + (spec.tickSize * 2.0) && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("KREV", "Keltner Band Reclaim Reversion", "LONG", index, bar.close, stop, target, settings.keltnerMaxHoldBars, "1-minute Keltner lower-band reclaim mean-reversion scalp in flatter VWAP/EMA conditions."));
					lastBucket = bucket;
				}
			}
			if (settings.allowShorts
				&& settings.allowKeltnerScalpShorts
				&& previous.close > previousUpperBand
				&& bar.close < upperBand
				&& bar.close < previous.low
				&& bar.rsi14 >= 52.0
				&& closeLocation <= 0.44
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeBearish(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
				double stop = compressedShortStop(spec, bars, index, bar.close, maxRiskTicks);
				double risk = stop - bar.close;
				double target = Math.max(bar.ema20, bar.close - (risk * settings.keltnerRewardRisk));
				if (risk > 0.0 && target < bar.close - (spec.tickSize * 2.0) && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("KREV", "Keltner Band Reclaim Reversion", "SHORT", index, bar.close, stop, target, settings.keltnerMaxHoldBars, "1-minute Keltner upper-band reclaim mean-reversion scalp in flatter VWAP/EMA conditions."));
					lastBucket = bucket;
				}
			}
			if (signals.size() >= settings.keltnerReversion.maxTradesPerDay) {
				break;
			}
		}
		return signals;
	}

	private static List<Signal> findMeanReversionSignals(InstrumentSpec spec, List<Bar> bars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		double[] rsi = rsi(bars, RSI_PERIOD);
		for (int index = 25; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			Bar previous = bars.get(index - 1);
			if (bar.marketTime.isBefore(MEAN_REVERSION_START) || bar.marketTime.isAfter(MEAN_REVERSION_END) || bar.vwap <= 0.0) {
				continue;
			}
			double distanceTicks = Math.abs(bar.close - bar.vwap) / spec.tickSize;
			if (distanceTicks < settings.meanReversionMinDistanceTicks) {
				continue;
			}
			if (bar.close < bar.vwap && rsi[index] < settings.meanReversionOversoldRsi && bar.close > previous.high && closeLocation(bar) >= 0.55) {
				double stop = Math.min(bar.low, previous.low) - (spec.tickSize * 2.0);
				signals.add(signal("MRVWAP", "VWAP Mean Reversion", "LONG", index, bar.close, stop, Math.min(bar.vwap, bar.close + ((bar.close - stop) * Math.max(settings.minRewardRisk, 1.35))), "Mean reversion long after stretched move below VWAP."));
			}
			if (settings.allowShorts && bar.close > bar.vwap && rsi[index] > settings.meanReversionOverboughtRsi && bar.close < previous.low && closeLocation(bar) <= 0.45) {
				double stop = Math.max(bar.high, previous.high) + (spec.tickSize * 2.0);
				signals.add(signal("MRVWAP", "VWAP Mean Reversion", "SHORT", index, bar.close, stop, Math.max(bar.vwap, bar.close - ((stop - bar.close) * Math.max(settings.minRewardRisk, 1.35))), "Mean reversion short after stretched move above VWAP."));
			}
		}
		return dedupeByHour(signals, settings.vwapMeanReversion.maxTradesPerDay);
	}

	private static List<Signal> findAfternoonContinuationSignals(InstrumentSpec spec, List<Bar> bars, List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		if (bars == null || bars.size() < 120) {
			return signals;
		}

		double maxRiskTicks = Math.min(settings.afternoonMaxRiskTicks, settings.maxInitialRiskTicks);
		double minTrendSlopeTicks = Math.max(1.0, settings.vwapMinTrendSlopeTicks * 0.5);
		for (int index = 60; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (bar.marketTime.isBefore(AFTERNOON_CONTINUATION_START) || bar.marketTime.isAfter(AFTERNOON_CONTINUATION_END) || bar.vwap <= 0.0) {
				continue;
			}
			double volumeRatio = bar.volumeSma20 <= 0.0 ? 1.0 : bar.volume / bar.volumeSma20;
			if (volumeRatio < settings.afternoonMinVolumeRatio) {
				continue;
			}
			double closeLocation = closeLocation(bar);
			double trendSlopeTicks = (bar.ema20 - bars.get(Math.max(0, index - 10)).ema20) / spec.tickSize;
			double channelHigh = recentSwingHigh(bars, index - 1, 30);
			double channelLow = recentSwingLow(bars, index - 1, 30);

			if (bar.close > channelHigh + spec.tickSize
				&& bar.close > bar.vwap
				&& bar.ema9 >= bar.ema20
				&& bar.close >= bar.ema50
				&& trendSlopeTicks >= minTrendSlopeTicks
				&& closeLocation >= 0.55
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeBreakoutLong(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
				double stop = compressedLongStop(spec, bars, index, bar.close, maxRiskTicks);
				double risk = bar.close - stop;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("AFT", "Afternoon Continuation", "LONG", index, bar.close, stop, bar.close + (risk * settings.afternoonRewardRisk), 45, "Afternoon local-range continuation long with VWAP, EMA, volume, and next-bar execution filters."));
				}
			}
			if (settings.allowShorts
				&& bar.close < channelLow - spec.tickSize
				&& bar.close < bar.vwap
				&& bar.ema9 <= bar.ema20
				&& bar.close <= bar.ema50
				&& trendSlopeTicks <= -minTrendSlopeTicks
				&& closeLocation <= 0.45
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeBearish(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
				double stop = compressedShortStop(spec, bars, index, bar.close, maxRiskTicks);
				double risk = stop - bar.close;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("AFT", "Afternoon Continuation", "SHORT", index, bar.close, stop, bar.close - (risk * settings.afternoonRewardRisk), 45, "Afternoon local-range continuation short with VWAP, EMA, volume, and next-bar execution filters."));
				}
			}
			if (signals.size() >= settings.afternoonContinuation.maxTradesPerDay) {
				break;
			}
		}
		return dedupeByHour(signals, settings.afternoonContinuation.maxTradesPerDay);
	}

	private static List<Signal> findMarketIntradayMomentumSignals(InstrumentSpec spec, List<Bar> bars, List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		if (bars == null || bars.size() < 300) {
			return signals;
		}

		Bar openBar = null;
		Bar firstHalfHourCloseBar = null;
		Bar lateReferenceBar = null;
		double openingHigh = Double.NEGATIVE_INFINITY;
		double openingLow = Double.POSITIVE_INFINITY;
		double openingVolumeRatioTotal = 0.0;
		int openingVolumeRatioCount = 0;
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (openBar == null && !bar.marketTime.isBefore(RTH_START)) {
				openBar = bar;
			}
			if (!bar.marketTime.isBefore(RTH_START) && bar.marketTime.isBefore(MARKET_INTRADAY_MOMENTUM_OPEN_END)) {
				firstHalfHourCloseBar = bar;
				openingHigh = Math.max(openingHigh, bar.high);
				openingLow = Math.min(openingLow, bar.low);
				if (bar.volumeSma20 > 0.0) {
					openingVolumeRatioTotal += bar.volume / bar.volumeSma20;
					openingVolumeRatioCount++;
				}
			}
			if (lateReferenceBar == null && !bar.marketTime.isBefore(MARKET_INTRADAY_MOMENTUM_LATE_START)) {
				lateReferenceBar = bar;
			}
		}
		if (openBar == null || firstHalfHourCloseBar == null || lateReferenceBar == null || openingHigh <= openingLow) {
			return signals;
		}

		double firstMoveTicks = (firstHalfHourCloseBar.close - openBar.open) / spec.tickSize;
		double openingRangeTicks = (openingHigh - openingLow) / spec.tickSize;
		double minimumOpeningMove = settings.marketIntradayMomentumMinOpenMoveTicks;
		if (Math.abs(firstMoveTicks) < minimumOpeningMove || openingRangeTicks < minimumOpeningMove * 1.2) {
			return signals;
		}

		double maxRiskTicks = Math.min(settings.marketIntradayMomentumMaxRiskTicks, settings.maxInitialRiskTicks);
		double averageOpeningVolumeRatio = openingVolumeRatioCount == 0 ? 1.0 : openingVolumeRatioTotal / openingVolumeRatioCount;
		boolean strongOpeningContext = openingRangeTicks >= minimumOpeningMove * 1.6
			|| averageOpeningVolumeRatio >= settings.marketIntradayMomentumMinVolumeRatio + 0.25;
		int pullbackSignals = 0;
		int lastPullbackBucket = -1;
		for (int index = 60; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (bar.vwap <= 0.0) {
				continue;
			}
			double volumeRatio = bar.volumeSma20 <= 0.0 ? 1.0 : bar.volume / bar.volumeSma20;
			if (volumeRatio < settings.marketIntradayMomentumMinVolumeRatio) {
				continue;
			}
			double closeLocation = closeLocation(bar);
		if (settings.marketIntradayMomentum.maxTradesPerDay > 1
				&& strongOpeningContext
				&& !bar.marketTime.isBefore(MARKET_IMPULSE_PULLBACK_START)
				&& !bar.marketTime.isAfter(MARKET_IMPULSE_PULLBACK_END)
				&& pullbackSignals < settings.marketIntradayMomentum.maxTradesPerDay) {
				Bar previous = bars.get(index - 1);
				double trendSlopeTicks = (bar.ema20 - bars.get(Math.max(0, index - 10)).ema20) / spec.tickSize;
				int bucket = ((bar.marketTime.getHour() * 60) + bar.marketTime.getMinute()) / 30;
				if (bucket != lastPullbackBucket) {
					if (firstMoveTicks > 0.0
						&& bar.close > bar.vwap
						&& bar.ema9 >= bar.ema20
						&& bar.ema20 >= bar.ema50
						&& trendSlopeTicks >= Math.max(1.0, settings.marketIntradayMomentumMinLateMoveTicks * 0.25)
						&& previous.low <= Math.max(previous.vwap, previous.ema20) + (spec.tickSize * 2.0)
						&& bar.close > previous.high
						&& closeLocation >= 0.55
						&& (!settings.requireHigherTimeframeGuard || higherTimeframeBreakoutLong(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
						double stop = compressedLongStop(spec, bars, index, bar.close, maxRiskTicks);
						double risk = bar.close - stop;
						if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
							signals.add(signal("IPB", "Opening Impulse Pullback", "LONG", index, bar.close, stop, bar.close + (risk * Math.max(0.85, settings.marketIntradayMomentumRewardRisk)), 25, "First-half-hour directional impulse followed by VWAP/EMA pullback continuation with opening volume/volatility confirmation."));
							pullbackSignals++;
							lastPullbackBucket = bucket;
						}
					}
					if (settings.allowShorts
						&& firstMoveTicks < 0.0
						&& bar.close < bar.vwap
						&& bar.ema9 <= bar.ema20
						&& bar.ema20 <= bar.ema50
						&& trendSlopeTicks <= -Math.max(1.0, settings.marketIntradayMomentumMinLateMoveTicks * 0.25)
						&& previous.high >= Math.min(previous.vwap, previous.ema20) - (spec.tickSize * 2.0)
						&& bar.close < previous.low
						&& closeLocation <= 0.45
						&& (!settings.requireHigherTimeframeGuard || higherTimeframeBearish(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
						double stop = compressedShortStop(spec, bars, index, bar.close, maxRiskTicks);
						double risk = stop - bar.close;
						if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
							signals.add(signal("IPB", "Opening Impulse Pullback", "SHORT", index, bar.close, stop, bar.close - (risk * Math.max(0.85, settings.marketIntradayMomentumRewardRisk)), 25, "First-half-hour directional impulse followed by VWAP/EMA pullback continuation with opening volume/volatility confirmation."));
							pullbackSignals++;
							lastPullbackBucket = bucket;
						}
					}
				}
			}

			if (bar.marketTime.isBefore(MARKET_INTRADAY_MOMENTUM_SIGNAL_START) || bar.marketTime.isAfter(MARKET_INTRADAY_MOMENTUM_SIGNAL_END)) {
				continue;
			}
			double lateMoveTicks = (bar.close - lateReferenceBar.open) / spec.tickSize;
			if (Math.abs(lateMoveTicks) < settings.marketIntradayMomentumMinLateMoveTicks || Math.signum(firstMoveTicks) != Math.signum(lateMoveTicks)) {
				continue;
			}
			if (firstMoveTicks > 0.0
				&& bar.close > bar.vwap
				&& bar.ema9 >= bar.ema20
				&& closeLocation >= 0.55
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeBreakoutLong(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
				double stop = compressedLongStop(spec, bars, index, bar.close, maxRiskTicks);
				double risk = bar.close - stop;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("MIM", "Market Intraday Momentum", "LONG", index, bar.close, stop, bar.close + (risk * settings.marketIntradayMomentumRewardRisk), 35, "Late-session long when first-half-hour and late-session futures momentum align with VWAP/EMA confirmation."));
					break;
				}
			}
			if (settings.allowShorts
				&& firstMoveTicks < 0.0
				&& bar.close < bar.vwap
				&& bar.ema9 <= bar.ema20
				&& closeLocation <= 0.45
				&& (!settings.requireHigherTimeframeGuard || higherTimeframeBearish(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
				double stop = compressedShortStop(spec, bars, index, bar.close, maxRiskTicks);
				double risk = stop - bar.close;
				if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxRiskTicks) {
					signals.add(signal("MIM", "Market Intraday Momentum", "SHORT", index, bar.close, stop, bar.close - (risk * settings.marketIntradayMomentumRewardRisk), 35, "Late-session short when first-half-hour and late-session futures momentum align with VWAP/EMA confirmation."));
					break;
				}
			}
		}
		return dedupeByHour(signals, settings.marketIntradayMomentum.maxTradesPerDay);
	}

	private static List<Signal> findSweepSignals(InstrumentSpec spec, List<Bar> bars, List<Bar> previousBars, List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		if (previousBars == null || previousBars.isEmpty()) {
			return signals;
		}
		double previousHigh = highest(previousBars);
		double previousLow = lowest(previousBars);
		double maxSweepRiskTicks = Math.min(DEFAULT_SWEEP_MAX_RISK_TICKS, settings.maxInitialRiskTicks);
		for (int index = 20; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			if (bar.marketTime.isBefore(SWEEP_START) || bar.marketTime.isAfter(SWEEP_END)) {
				continue;
			}
			if (bar.low < previousLow - spec.tickSize && bar.close > previousLow && bar.close > bar.open) {
				boolean lateAfternoon = !bar.marketTime.isBefore(LocalTime.of(13, 45));
				double reclaimTicks = (bar.close - previousLow) / spec.tickSize;
				double closeLocation = closeLocation(bar);
				if (settings.requireHigherTimeframeGuard && !higherTimeframeConstructive(fifteenMinuteBars, oneHourBars, bar.marketTime)) {
					continue;
				}
				if (lateAfternoon && settings.enableLateSweep) {
					if (reclaimTicks < settings.lateSweepReclaimTicks || closeLocation < settings.lateSweepCloseLocation) {
						continue;
					}
					double stop = bar.low - (spec.tickSize * 2.0);
					if (riskTicks(spec, bar.close, stop) > maxSweepRiskTicks) {
						continue;
					}
					signals.add(signal("SWEEP", "Prior-Day Liquidity Sweep", "LONG", index, bar.close, stop, bar.close + ((bar.close - stop) * Math.max(settings.minRewardRisk, 1.0)), "Late-afternoon long after sweeping prior-day low and closing back above it."));
				}
				if (!lateAfternoon && settings.enableEarlySweep && settings.enableSweepSecondChance) {
					if (reclaimTicks < settings.earlySweepReclaimTicks || closeLocation < settings.sweepCloseLocation || bar.bodyPct < settings.minBodyPct || index + 1 >= bars.size()) {
						continue;
					}
					Bar confirmation = bars.get(index + 1);
					if (confirmation.close <= bar.high || confirmation.close <= previousLow + (spec.tickSize * 2.0)) {
						continue;
					}
					double stop = Math.min(bar.low, confirmation.low) - (spec.tickSize * 2.0);
					if (riskTicks(spec, confirmation.close, stop) > maxSweepRiskTicks) {
						continue;
					}
					signals.add(signal("SWEEP2", "Confirmed Prior-Day Sweep", "LONG", index + 1, confirmation.close, stop, confirmation.close + ((confirmation.close - stop) * Math.max(settings.minRewardRisk, 1.25)), "Early-afternoon confirmed long after prior-day low sweep, strong reclaim, and next-bar continuation."));
				}
				if (signals.size() >= settings.sweep.maxTradesPerDay) {
					break;
				}
			}
			if (settings.allowShorts && bar.high > previousHigh + spec.tickSize && bar.close < previousHigh && bar.close < bar.open) {
				boolean lateAfternoon = !bar.marketTime.isBefore(LocalTime.of(13, 45));
				double reclaimTicks = (previousHigh - bar.close) / spec.tickSize;
				double closeLocation = 1.0 - closeLocation(bar);
				if (settings.requireHigherTimeframeGuard && higherTimeframeConstructive(fifteenMinuteBars, oneHourBars, bar.marketTime)) {
					continue;
				}
				if (!lateAfternoon || reclaimTicks < settings.lateSweepReclaimTicks || closeLocation < settings.lateSweepCloseLocation) {
					continue;
				}
				double stop = bar.high + (spec.tickSize * 2.0);
				if (riskTicks(spec, bar.close, stop) > maxSweepRiskTicks) {
					continue;
				}
				signals.add(signal("SWEEP", "Prior-Day Liquidity Sweep", "SHORT", index, bar.close, stop, bar.close - ((stop - bar.close) * Math.max(settings.minRewardRisk, 1.0)), "Late-afternoon short after sweeping prior-day high and closing back below it."));
				if (signals.size() >= settings.sweep.maxTradesPerDay) {
					break;
				}
			}
		}
		return dedupeByHour(signals, settings.sweep.maxTradesPerDay);
	}

	private static List<Signal> findFvgSignals(InstrumentSpec spec, List<Bar> bars) {
		List<Signal> signals = new ArrayList<Signal>();
		for (int index = 2; index < bars.size(); index++) {
			Bar first = bars.get(index - 2);
			Bar middle = bars.get(index - 1);
			Bar third = bars.get(index);
			if (third.marketTime.isBefore(LocalTime.of(10, 0)) || third.marketTime.isAfter(LocalTime.of(15, 0))) {
				continue;
			}
				if (first.low > third.high && middle.close < middle.open) {
					double gapLow = third.high;
					double gapHigh = first.low;
					double widthTicks = (gapHigh - gapLow) / spec.tickSize;
					if (widthTicks < 4.0) {
						continue;
					}
					for (int entryIndex = index + 1; entryIndex < Math.min(index + 8, bars.size()); entryIndex++) {
						Bar entry = bars.get(entryIndex);
						if (entry.high >= gapLow && entry.close < gapLow && entry.close < entry.open) {
							double stop = gapHigh + (spec.tickSize * 2.0);
							signals.add(signal("FVG", "Fair Value Gap Reclaim", "SHORT", entryIndex, entry.close, stop, entry.close - ((stop - entry.close) * 1.2), "Bearish futures FVG reclaim."));
							break;
					}
				}
			}
		}
		return dedupeByHour(signals, 2);
	}

	private static List<Signal> findCloseMomentumSignals(InstrumentSpec spec, List<Bar> bars, List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, FuturesStrategySettings settings) {
		List<Signal> signals = new ArrayList<Signal>();
		if (bars == null || bars.size() < 120) {
			return signals;
		}

		double rthOpen = bars.get(0).open;
		double sessionHigh = bars.get(0).high;
		double sessionLow = bars.get(0).low;
		double maxCloseMomentumRiskTicks = Math.min(settings.maxInitialRiskTicks, 80.0);
		double minSlopeTicks = Math.max(1.0, settings.vwapMinTrendSlopeTicks * 0.5);

		for (int index = 1; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			double priorHigh = sessionHigh;
			double priorLow = sessionLow;

			if (!bar.marketTime.isBefore(CLOSE_MOMENTUM_START) && !bar.marketTime.isAfter(CLOSE_MOMENTUM_END)) {
				double moveTicks = Math.abs(bar.close - rthOpen) / spec.tickSize;
				double volumeRatio = bar.volumeSma20 <= 0.0 ? 1.0 : bar.volume / bar.volumeSma20;
				if (moveTicks >= settings.closeMomentumMinMoveTicks && volumeRatio >= settings.closeMomentumVolumeRatio && bar.vwap > 0.0) {
					double closeLocation = closeLocation(bar);
					double trendSlopeTicks = (bar.ema20 - bars.get(Math.max(0, index - 10)).ema20) / spec.tickSize;
					if (settings.allowCloseMomentumLongs
						&& bar.close > rthOpen
						&& bar.close > bar.vwap
						&& bar.ema9 >= bar.ema20
						&& bar.ema20 >= bar.ema50
						&& trendSlopeTicks >= minSlopeTicks
						&& bar.close >= priorHigh - spec.tickSize
						&& closeLocation >= 0.58
						&& (!settings.requireHigherTimeframeGuard || higherTimeframeBreakoutLong(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
						double stop = Math.min(recentSwingLow(bars, index, 8), bar.ema20) - (spec.tickSize * 2.0);
						double risk = bar.close - stop;
						if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxCloseMomentumRiskTicks) {
							signals.add(signal("CMOM", "Close Momentum", "LONG", index, bar.close, stop, bar.close + (risk * settings.closeMomentumRewardRisk), 40, "Late-session trend continuation after rest-of-day momentum confirms near session high."));
						}
					}
					if (settings.allowShorts
						&& settings.allowCloseMomentumShorts
						&& bar.close < rthOpen
						&& bar.close < bar.vwap
						&& bar.ema9 <= bar.ema20
						&& bar.ema20 <= bar.ema50
						&& trendSlopeTicks <= -minSlopeTicks
						&& bar.close <= priorLow + spec.tickSize
						&& closeLocation <= 0.42
						&& (!settings.requireHigherTimeframeGuard || higherTimeframeBearish(fifteenMinuteBars, oneHourBars, bar.marketTime))) {
						double stop = Math.max(recentSwingHigh(bars, index, 8), bar.ema20) + (spec.tickSize * 2.0);
						double risk = stop - bar.close;
						if (risk > 0.0 && riskTicks(spec, bar.close, stop) <= maxCloseMomentumRiskTicks) {
							signals.add(signal("CMOM", "Close Momentum", "SHORT", index, bar.close, stop, bar.close - (risk * settings.closeMomentumRewardRisk), 40, "Late-session trend continuation after rest-of-day momentum confirms near session low."));
						}
					}
				}
				if (signals.size() >= settings.closeMomentum.maxTradesPerDay) {
					break;
				}
			}

			sessionHigh = Math.max(sessionHigh, bar.high);
			sessionLow = Math.min(sessionLow, bar.low);
		}
		return dedupeByHour(signals, settings.closeMomentum.maxTradesPerDay);
	}

	private static Signal signal(String code, String name, String side, int entryIndex, double entry, double stop, double target, String notes) {
		return signal(code, name, side, entryIndex, entry, stop, target, 0, notes);
	}

	private static Signal signal(String code, String name, String side, int entryIndex, double entry, double stop, double target, int maxHoldBars, String notes) {
		Signal signal = new Signal();
		signal.strategyCode = code;
		signal.strategyName = name;
		signal.side = side;
		signal.entryIndex = entryIndex;
		signal.entryPrice = entry;
		signal.stopPrice = stop;
		signal.targetPrice = target;
		signal.maxHoldBars = maxHoldBars;
		signal.notes = notes;
		return signal;
	}

	private static List<Signal> dedupeByHour(List<Signal> signals, int maxPerStrategy) {
		Collections.sort(signals, new Comparator<Signal>() {
			@Override
			public int compare(Signal first, Signal second) {
				return first.entryIndex - second.entryIndex;
			}
		});
		List<Signal> filtered = new ArrayList<Signal>();
		Map<String, Integer> counts = new HashMap<String, Integer>();
		int lastIndex = -999;
		for (int index = 0; index < signals.size(); index++) {
			Signal signal = signals.get(index);
			int count = countFor(counts, signal.strategyCode);
			if (count >= maxPerStrategy) {
				continue;
			}
			if (signal.entryIndex - lastIndex < 20) {
				continue;
			}
			filtered.add(signal);
			counts.put(signal.strategyCode, count + 1);
			lastIndex = signal.entryIndex;
		}
		return filtered;
	}

	private static int saveBacktest(BacktestResult result, BacktestConfig config) {
		String insertBacktest = "INSERT INTO FuturesBacktests (symbol, contractName, startDate, endDate, startingBalance, endingBalance, totalProfit, returnPct, winRate, numTrades, profitFactor, maxDrawdownPct, maxTrailingDrawdown, dailyLossLimit, maxRiskPerTrade, maxContracts, trailingThreshold, ruleViolation, ruleMessage, dataSource, createdAt) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(insertBacktest, Statement.RETURN_GENERATED_KEYS)) {
			pstmt.setString(1, result.symbol);
			pstmt.setString(2, result.contractName);
			pstmt.setString(3, result.startDate);
			pstmt.setString(4, result.endDate);
			pstmt.setDouble(5, result.startingBalance);
			pstmt.setDouble(6, result.endingBalance);
			pstmt.setDouble(7, result.totalProfit);
			pstmt.setDouble(8, result.returnPct);
			pstmt.setDouble(9, result.winRate);
			pstmt.setInt(10, result.trades);
			pstmt.setDouble(11, result.profitFactor);
			pstmt.setDouble(12, result.maxDrawdownPct);
			pstmt.setDouble(13, config.maxTrailingDrawdown);
			pstmt.setDouble(14, config.dailyLossLimit);
			pstmt.setDouble(15, config.maxRiskPerTrade);
			pstmt.setInt(16, config.maxContracts);
			pstmt.setDouble(17, result.trailingThreshold);
			pstmt.setInt(18, result.ruleViolation ? 1 : 0);
			pstmt.setString(19, result.ruleMessage);
			pstmt.setString(20, result.dataSource);
			pstmt.setString(21, LocalDateTime.now().format(DISPLAY_TIME_FORMAT));
			pstmt.executeUpdate();

			int backtestId = -1;
			try (ResultSet keys = pstmt.getGeneratedKeys()) {
				if (keys.next()) {
					backtestId = keys.getInt(1);
				}
			}
			if (backtestId <= 0) {
				return -1;
			}
			insertTrades(conn, backtestId, result.tradeRecords);
			return backtestId;
		} catch (SQLException e) {
			e.printStackTrace();
			return -1;
		}
	}

	private static int savePortfolioBacktest(PortfolioBacktestResult result, PortfolioBacktestConfig config) {
		String insertBacktest = "INSERT INTO FuturesPortfolioBacktests (fundedProfile, symbols, startDate, endDate, startingBalance, endingBalance, totalProfit, returnPct, winRate, numTrades, profitFactor, maxDrawdownPct, maxTrailingDrawdown, dailyLossLimit, maxRiskPerTrade, maxContracts, maxOpenPositions, maxAggregateContracts, maxAggregateMiniUnits, maxConcurrentPositions, maxConcurrentContracts, maxConcurrentMiniUnits, maxNotionalExposure, maxIntradayLoss, maxAggregateMae, trailingThreshold, dailyLossBreaches, trailingDrawdownBreaches, maeBreaches, overlapRejections, exposureRejections, riskRejections, ruleViolation, ruleMessage, dataSource, createdAt) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(insertBacktest, Statement.RETURN_GENERATED_KEYS)) {
			pstmt.setString(1, result.fundedProfile);
			pstmt.setString(2, result.symbols);
			pstmt.setString(3, result.startDate);
			pstmt.setString(4, result.endDate);
			pstmt.setDouble(5, result.startingBalance);
			pstmt.setDouble(6, result.endingBalance);
			pstmt.setDouble(7, result.totalProfit);
			pstmt.setDouble(8, result.returnPct);
			pstmt.setDouble(9, result.winRate);
			pstmt.setInt(10, result.trades);
			pstmt.setDouble(11, result.profitFactor);
			pstmt.setDouble(12, result.maxDrawdownPct);
			pstmt.setDouble(13, config.maxTrailingDrawdown);
			pstmt.setDouble(14, config.dailyLossLimit);
			pstmt.setDouble(15, config.maxRiskPerTrade);
			pstmt.setInt(16, config.maxContracts);
			pstmt.setInt(17, config.maxOpenPositions);
			pstmt.setInt(18, config.maxAggregateContracts);
			pstmt.setDouble(19, config.maxAggregateMiniUnits);
			pstmt.setInt(20, result.maxConcurrentPositions);
			pstmt.setInt(21, result.maxConcurrentContracts);
			pstmt.setDouble(22, result.maxConcurrentMiniUnits);
			pstmt.setDouble(23, result.maxNotionalExposure);
			pstmt.setDouble(24, result.maxIntradayLoss);
			pstmt.setDouble(25, result.maxAggregateMae);
			pstmt.setDouble(26, result.trailingThreshold);
			pstmt.setInt(27, result.dailyLossBreaches);
			pstmt.setInt(28, result.trailingDrawdownBreaches);
			pstmt.setInt(29, result.maeBreaches);
			pstmt.setInt(30, result.overlapRejections);
			pstmt.setInt(31, result.exposureRejections);
			pstmt.setInt(32, result.riskRejections);
			pstmt.setInt(33, result.ruleViolation ? 1 : 0);
			pstmt.setString(34, result.ruleMessage);
			pstmt.setString(35, result.dataSource);
			pstmt.setString(36, LocalDateTime.now().format(DISPLAY_TIME_FORMAT));
			pstmt.executeUpdate();

			int backtestId = -1;
			try (ResultSet keys = pstmt.getGeneratedKeys()) {
				if (keys.next()) {
					backtestId = keys.getInt(1);
				}
			}
			if (backtestId <= 0) {
				return -1;
			}
			insertPortfolioTrades(conn, backtestId, result.tradeRecords);
			return backtestId;
		} catch (SQLException e) {
			e.printStackTrace();
			return -1;
		}
	}

	private static void insertTrades(Connection conn, int backtestId, List<FuturesTrade> trades) throws SQLException {
		String sql = "INSERT INTO FuturesTrades (futuresBacktestID, symbol, strategyCode, strategyName, side, contracts, entryPrice, exitPrice, stopPrice, targetPrice, openedAt, closedAt, pnl, mfe, mae, exitReason, tradeNotes) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			for (int index = 0; index < trades.size(); index++) {
				FuturesTrade trade = trades.get(index);
				pstmt.setInt(1, backtestId);
				pstmt.setString(2, trade.symbol);
				pstmt.setString(3, trade.strategyCode);
				pstmt.setString(4, trade.strategyName);
				pstmt.setString(5, trade.side);
				pstmt.setInt(6, trade.contracts);
				pstmt.setDouble(7, trade.entryPrice);
				pstmt.setDouble(8, trade.exitPrice);
				pstmt.setDouble(9, trade.stopPrice);
				pstmt.setDouble(10, trade.targetPrice);
				pstmt.setString(11, trade.openedAt);
				pstmt.setString(12, trade.closedAt);
				pstmt.setDouble(13, trade.pnl);
				pstmt.setDouble(14, trade.mfe);
				pstmt.setDouble(15, trade.mae);
				pstmt.setString(16, trade.exitReason);
				pstmt.setString(17, trade.notes);
				pstmt.addBatch();
			}
			pstmt.executeBatch();
		}
	}

	private static void insertPortfolioTrades(Connection conn, int backtestId, List<FuturesTrade> trades) throws SQLException {
		String sql = "INSERT INTO FuturesPortfolioTrades (portfolioBacktestID, symbol, strategyCode, strategyName, side, contracts, entryPrice, exitPrice, stopPrice, targetPrice, openedAt, closedAt, pnl, mfe, mae, exitReason, tradeNotes) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			for (int index = 0; index < trades.size(); index++) {
				FuturesTrade trade = trades.get(index);
				pstmt.setInt(1, backtestId);
				pstmt.setString(2, trade.symbol);
				pstmt.setString(3, trade.strategyCode);
				pstmt.setString(4, trade.strategyName);
				pstmt.setString(5, trade.side);
				pstmt.setInt(6, trade.contracts);
				pstmt.setDouble(7, trade.entryPrice);
				pstmt.setDouble(8, trade.exitPrice);
				pstmt.setDouble(9, trade.stopPrice);
				pstmt.setDouble(10, trade.targetPrice);
				pstmt.setString(11, trade.openedAt);
				pstmt.setString(12, trade.closedAt);
				pstmt.setDouble(13, trade.pnl);
				pstmt.setDouble(14, trade.mfe);
				pstmt.setDouble(15, trade.mae);
				pstmt.setString(16, trade.exitReason);
				pstmt.setString(17, trade.notes);
				pstmt.addBatch();
			}
			pstmt.executeBatch();
		}
	}

	private static class DataBundle {
		private List<Bar> bars = new ArrayList<Bar>();
		private String source = "none";
	}

	private static DataBundle loadBars(String symbol, LocalDate startDate, LocalDate endDate) {
		InstrumentSpec spec = instrumentFor(symbol);
		DataBundle futures = loadCsv(new File(DATA_DIR + "/" + TIMEFRAME_FOLDER + "/" + spec.symbol + ".csv"), startDate, endDate, 1.0, "native futures csv");
		if (!futures.bars.isEmpty()) {
			return futures;
		}
		DataBundle proxy = loadCsv(new File("market_data/1min/" + spec.proxySymbol + ".csv"), startDate, endDate, spec.proxyScale, "equity proxy " + spec.proxySymbol + " scaled for futures development");
		if (!proxy.bars.isEmpty()) {
			return proxy;
		}
		DataBundle synthetic = new DataBundle();
		synthetic.bars = syntheticBars(spec, startDate, endDate);
		synthetic.source = "deterministic synthetic futures bars";
		return synthetic;
	}

	private static DataBundle loadNativeFuturesBars(String symbol, LocalDate startDate, LocalDate endDate, String timeframeFolder) {
		InstrumentSpec spec = instrumentFor(symbol);
		return loadCsv(new File(DATA_DIR + "/" + timeframeFolder + "/" + spec.symbol + ".csv"), startDate, endDate, 1.0, "native futures csv " + timeframeFolder);
	}

	private static DataBundle loadCsv(File file, LocalDate startDate, LocalDate endDate, double scale, String source) {
		return loadCsv(file, startDate, endDate, scale, source, true);
	}

	private static DataBundle loadCsv(File file, LocalDate startDate, LocalDate endDate, double scale, String source, boolean rthOnly) {
		DataBundle bundle = new DataBundle();
		if (file == null || !file.exists()) {
			return bundle;
		}
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String header = reader.readLine();
			if (header == null) {
				return bundle;
			}
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				if (parts.length < 6) {
					continue;
				}
				Bar bar = parseBar(parts, scale);
				if (bar == null || bar.marketDate.isBefore(startDate) || bar.marketDate.isAfter(endDate)) {
					continue;
				}
				if (rthOnly && (bar.marketTime.isBefore(RTH_START) || !bar.marketTime.isBefore(RTH_END))) {
					continue;
				}
				bundle.bars.add(bar);
			}
			bundle.source = source;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return bundle;
	}

	private static Bar parseBar(String[] parts, double scale) {
		try {
			ZonedDateTime timestamp;
			String rawTimestamp = parts[0].trim();
			if (rawTimestamp.endsWith("Z") || rawTimestamp.contains("+")) {
				timestamp = OffsetDateTime.parse(rawTimestamp.replace("Z", "+00:00")).atZoneSameInstant(NEW_YORK_ZONE);
			} else {
				timestamp = LocalDateTime.parse(rawTimestamp).atZone(NEW_YORK_ZONE);
			}
			Bar bar = new Bar();
			bar.marketDate = timestamp.toLocalDate();
			bar.marketTime = timestamp.toLocalTime();
			bar.displayTime = bar.marketDate.toString() + " " + (bar.marketTime.getSecond() == 0 && bar.marketTime.getNano() == 0
				? String.format("%02d:%02d", bar.marketTime.getHour(), bar.marketTime.getMinute())
				: String.format("%02d:%02d:%02d", bar.marketTime.getHour(), bar.marketTime.getMinute(), bar.marketTime.getSecond()));
			bar.open = parseDouble(parts[1]) * scale;
			bar.high = parseDouble(parts[2]) * scale;
			bar.low = parseDouble(parts[3]) * scale;
			bar.close = parseDouble(parts[4]) * scale;
			bar.volume = parseDouble(parts[5]);
			bar.vwap = parts.length > 6 && parseDouble(parts[6]) > 0.0 ? parseDouble(parts[6]) * scale : ((bar.high + bar.low + bar.close) / 3.0);
			bar.ema9 = parts.length > 7 ? parseDouble(parts[7]) * scale : bar.close;
			bar.ema20 = parts.length > 8 ? parseDouble(parts[8]) * scale : bar.close;
			bar.ema50 = parts.length > 9 ? parseDouble(parts[9]) * scale : bar.close;
			bar.atr14 = parts.length > 10 ? parseDouble(parts[10]) * scale : 0.0;
			bar.rsi14 = parts.length > 11 ? parseDouble(parts[11]) : 50.0;
			bar.volumeSma20 = parts.length > 12 ? parseDouble(parts[12]) : 0.0;
			bar.rangeTicks = parts.length > 13 ? parseDouble(parts[13]) : 0.0;
			bar.bodyPct = parts.length > 14 ? parseDouble(parts[14]) : defaultBodyPct(bar);
			return bar;
		} catch (Exception e) {
			return null;
		}
	}

	private static List<Bar> syntheticBars(InstrumentSpec spec, LocalDate startDate, LocalDate endDate) {
		List<Bar> bars = new ArrayList<Bar>();
		Random random = new Random(spec.symbol.hashCode());
		double price = "MNQ".equals(spec.symbol) || "NQ".equals(spec.symbol) ? 19000.0 : ("M2K".equals(spec.symbol) ? 2200.0 : ("MGC".equals(spec.symbol) || "GC".equals(spec.symbol) ? 2300.0 : 5200.0));
		for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
			if (day.getDayOfWeek().getValue() >= 6) {
				continue;
			}
			double drift = (random.nextDouble() - 0.45) * spec.tickSize * 10.0;
			double cumulativePv = 0.0;
			double cumulativeVol = 0.0;
			for (int minute = 0; minute < 390; minute++) {
				LocalTime time = RTH_START.plusMinutes(minute);
				double open = price;
				double wave = Math.sin(minute / 19.0) * spec.tickSize * 6.0;
				double noise = (random.nextDouble() - 0.5) * spec.tickSize * 12.0;
				double close = roundToTick(spec, open + drift + wave + noise);
				double high = roundToTick(spec, Math.max(open, close) + spec.tickSize * (1.0 + random.nextInt(4)));
				double low = roundToTick(spec, Math.min(open, close) - spec.tickSize * (1.0 + random.nextInt(4)));
				double volume = 800 + random.nextInt(2400);
				cumulativePv += ((high + low + close) / 3.0) * volume;
				cumulativeVol += volume;
				Bar bar = new Bar();
				bar.marketDate = day;
				bar.marketTime = time;
				bar.displayTime = day.toString() + " " + String.format("%02d:%02d", time.getHour(), time.getMinute());
				bar.open = roundToTick(spec, open);
				bar.high = high;
				bar.low = low;
				bar.close = close;
				bar.volume = volume;
				bar.vwap = roundToTick(spec, cumulativePv / Math.max(1.0, cumulativeVol));
				bars.add(bar);
				price = close;
			}
		}
		return bars;
	}

	private static Map<LocalDate, List<Bar>> groupByDay(List<Bar> bars) {
		Map<LocalDate, List<Bar>> byDay = new HashMap<LocalDate, List<Bar>>();
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			List<Bar> dayBars = byDay.get(bar.marketDate);
			if (dayBars == null) {
				dayBars = new ArrayList<Bar>();
				byDay.put(bar.marketDate, dayBars);
			}
			dayBars.add(bar);
		}
		return byDay;
	}

	private static List<Bar> previousDayBars(Map<LocalDate, List<Bar>> byDay, List<LocalDate> days, int dayIndex) {
		if (dayIndex <= 0) {
			return new ArrayList<Bar>();
		}
		List<Bar> bars = byDay.get(days.get(dayIndex - 1));
		return bars == null ? new ArrayList<Bar>() : bars;
	}

	private static List<InstrumentSpec> supportedInstruments() {
		List<InstrumentSpec> specs = new ArrayList<InstrumentSpec>();
		specs.add(spec("MES", "Micro E-mini S&P 500", "CME", 0.25, 1.25, 5.0, 10, 24, 1.25, "SPY", 10.0));
		specs.add(spec("MNQ", "Micro E-mini Nasdaq-100", "CME", 0.25, 0.50, 2.0, 10, 48, 1.5, "QQQ", 45.0));
		specs.add(spec("M2K", "Micro E-mini Russell 2000", "CME", 0.10, 0.50, 5.0, 10, 30, 1.25, "IWM", 10.0));
		specs.add(spec("ES", "E-mini S&P 500", "CME", 0.25, 12.50, 50.0, 2, 12, 1.25, "SPY", 10.0));
		specs.add(spec("NQ", "E-mini Nasdaq-100", "CME", 0.25, 5.00, 20.0, 2, 32, 1.5, "QQQ", 45.0));
		specs.add(spec("MGC", "Micro Gold", "COMEX", 0.10, 1.00, 10.0, 10, 30, 1.25, "GLD", 10.0));
		specs.add(spec("GC", "Gold", "COMEX", 0.10, 10.00, 100.0, 1, 30, 1.25, "GLD", 10.0));
		return specs;
	}

	private static InstrumentSpec spec(String symbol, String name, String exchange, double tickSize, double tickValue, double pointValue, int maxContracts, double defaultStopTicks, double defaultTargetR, String proxySymbol, double proxyScale) {
		InstrumentSpec spec = new InstrumentSpec();
		spec.symbol = symbol;
		spec.name = name;
		spec.exchange = exchange;
		spec.tickSize = tickSize;
		spec.tickValue = tickValue;
		spec.pointValue = pointValue;
		spec.defaultMaxContracts = maxContracts;
		spec.defaultStopTicks = defaultStopTicks;
		spec.defaultTargetR = defaultTargetR;
		spec.proxySymbol = proxySymbol;
		spec.proxyScale = proxyScale;
		return spec;
	}

	private static InstrumentSpec instrumentFor(String symbol) {
		String normalized = normalizeSymbol(symbol);
		List<InstrumentSpec> specs = supportedInstruments();
		for (int index = 0; index < specs.size(); index++) {
			if (specs.get(index).symbol.equals(normalized)) {
				return specs.get(index);
			}
		}
		return specs.get(1);
	}

	private static String normalizeSymbol(String symbol) {
		if (symbol == null || symbol.trim().isEmpty()) {
			return "MNQ";
		}
		String normalized = symbol.trim().toUpperCase().replace("/", "");
		if ("MES".equals(normalized) || "MNQ".equals(normalized) || "M2K".equals(normalized) || "ES".equals(normalized) || "NQ".equals(normalized)
			|| "MGC".equals(normalized) || "GC".equals(normalized)) {
			return normalized;
		}
		return "MNQ";
	}

	private static boolean isMicroFuturesSymbol(String symbol) {
		String normalized = normalizeSymbol(symbol);
		return "MES".equals(normalized) || "MNQ".equals(normalized) || "M2K".equals(normalized) || "MGC".equals(normalized);
	}

	private static String normalizeExecutionMode(String mode) {
		if (mode == null || mode.trim().isEmpty()) {
			return "SIMULATED";
		}
		String normalized = mode.trim().toUpperCase();
		if ("TRADOVATE_DIRECT".equals(normalized) || "TOPSTEPX".equals(normalized) || "NINJATRADER_ATI".equals(normalized) || "RITHMIC".equals(normalized) || "TRADINGVIEW_WEBHOOK".equals(normalized)) {
			return normalized;
		}
		return "SIMULATED";
	}

	private static int maxTradesPerDay(String strategyCode, FuturesStrategySettings settings) {
		FuturesStrategySettings safeSettings = settings == null ? defaultFuturesStrategySettings() : settings;
		if ("ORB".equals(strategyCode) || "ORB2".equals(strategyCode)) {
			return safeSettings.orb.maxTradesPerDay;
		}
		if ("OMOM".equals(strategyCode)) {
			return safeSettings.openingMomentum.maxTradesPerDay;
		}
		if ("SWEEP".equals(strategyCode) || "SWEEP2".equals(strategyCode)) {
			return safeSettings.sweep.maxTradesPerDay;
		}
		if ("VWAP".equals(strategyCode)) {
			return safeSettings.vwapPullback.maxTradesPerDay;
		}
		if ("MRVWAP".equals(strategyCode)) {
			return safeSettings.vwapMeanReversion.maxTradesPerDay;
		}
		if ("FVG".equals(strategyCode)) {
			return safeSettings.fvg.maxTradesPerDay;
		}
		if ("CMOM".equals(strategyCode)) {
			return safeSettings.closeMomentum.maxTradesPerDay;
		}
		if ("AFT".equals(strategyCode)) {
			return safeSettings.afternoonContinuation.maxTradesPerDay;
		}
		if ("MIM".equals(strategyCode)) {
			return safeSettings.marketIntradayMomentum.maxTradesPerDay;
		}
		if ("IPB".equals(strategyCode)) {
			return safeSettings.marketIntradayMomentum.maxTradesPerDay;
		}
		if ("MSCALP".equals(strategyCode)) {
			return safeSettings.microScalp.maxTradesPerDay;
		}
		if ("KELT".equals(strategyCode)) {
			return safeSettings.keltnerScalp.maxTradesPerDay;
		}
		if ("KREV".equals(strategyCode)) {
			return safeSettings.keltnerReversion.maxTradesPerDay;
		}
		return 1;
	}

	private static int countFor(Map<String, Integer> counts, String key) {
		Integer count = counts.get(key);
		return count == null ? 0 : count.intValue();
	}

	private static double[] ema(List<Bar> bars, int period) {
		double[] values = new double[bars.size()];
		double multiplier = 2.0 / (period + 1.0);
		for (int index = 0; index < bars.size(); index++) {
			if (index == 0) {
				values[index] = bars.get(index).close;
			} else {
				values[index] = ((bars.get(index).close - values[index - 1]) * multiplier) + values[index - 1];
			}
		}
		return values;
	}

	private static double[] rsi(List<Bar> bars, int period) {
		double[] values = new double[bars.size()];
		double gains = 0.0;
		double losses = 0.0;
		for (int index = 1; index < bars.size(); index++) {
			double change = bars.get(index).close - bars.get(index - 1).close;
			double gain = Math.max(0.0, change);
			double loss = Math.max(0.0, -change);
			if (index <= period) {
				gains += gain;
				losses += loss;
				values[index] = 50.0;
				continue;
			}
			if (index == period + 1) {
				gains = gains / period;
				losses = losses / period;
			} else {
				gains = ((gains * (period - 1)) + gain) / period;
				losses = ((losses * (period - 1)) + loss) / period;
			}
			if (losses == 0.0) {
				values[index] = 100.0;
			} else {
				double rs = gains / losses;
				values[index] = 100.0 - (100.0 / (1.0 + rs));
			}
		}
		return values;
	}

	private static double averageVolume(List<Bar> bars, int index, int period) {
		int start = Math.max(0, index - period);
		int count = 0;
		double total = 0.0;
		for (int cursor = start; cursor < index; cursor++) {
			total += bars.get(cursor).volume;
			count++;
		}
		return count == 0 ? 0.0 : total / count;
	}

	private static double closeLocation(Bar bar) {
		double range = bar.high - bar.low;
		if (range <= 0.0) {
			return 0.5;
		}
		return (bar.close - bar.low) / range;
	}

	private static double riskTicks(InstrumentSpec spec, double entry, double stop) {
		if (spec.tickSize <= 0.0) {
			return 0.0;
		}
		return Math.abs(entry - stop) / spec.tickSize;
	}

	private static double defaultBodyPct(Bar bar) {
		double range = bar.high - bar.low;
		if (range <= 0.0) {
			return 0.0;
		}
		return (Math.abs(bar.close - bar.open) / range) * 100.0;
	}

	private static boolean higherTimeframeConstructive(List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, LocalTime entryTime) {
		Bar fifteenMinute = latestClosedBar(fifteenMinuteBars, entryTime, 15);
		if (fifteenMinute != null && fifteenMinute.ema20 > 0.0 && fifteenMinute.ema50 > 0.0) {
			boolean deeplyBearish = fifteenMinute.close < fifteenMinute.ema20 && fifteenMinute.ema20 < fifteenMinute.ema50;
			if (deeplyBearish) {
				return false;
			}
		}

		Bar oneHour = latestClosedBar(oneHourBars, entryTime, 60);
		if (oneHour != null && oneHour.ema20 > 0.0 && oneHour.ema9 > 0.0) {
			boolean hourlyBearish = oneHour.close < oneHour.ema20 && oneHour.ema9 < oneHour.ema20;
			if (hourlyBearish) {
				return false;
			}
		}

		return true;
	}

	private static boolean higherTimeframeBreakoutLong(List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, LocalTime entryTime) {
		Bar fifteenMinute = latestClosedBar(fifteenMinuteBars, entryTime, 15);
		if (fifteenMinute != null && fifteenMinute.ema20 > 0.0 && fifteenMinute.ema50 > 0.0) {
			if (fifteenMinute.close < fifteenMinute.ema20 || fifteenMinute.ema20 < fifteenMinute.ema50) {
				return false;
			}
		}

		Bar oneHour = latestClosedBar(oneHourBars, entryTime, 60);
		if (oneHour != null && oneHour.ema20 > 0.0 && oneHour.ema9 > 0.0) {
			if (oneHour.close < oneHour.ema20 || oneHour.ema9 < oneHour.ema20) {
				return false;
			}
		}

		return true;
	}

	private static boolean higherTimeframeBearish(List<Bar> fifteenMinuteBars, List<Bar> oneHourBars, LocalTime entryTime) {
		Bar fifteenMinute = latestClosedBar(fifteenMinuteBars, entryTime, 15);
		if (fifteenMinute != null && fifteenMinute.ema20 > 0.0 && fifteenMinute.ema50 > 0.0) {
			if (fifteenMinute.close > fifteenMinute.ema20 || fifteenMinute.ema20 > fifteenMinute.ema50) {
				return false;
			}
		}

		Bar oneHour = latestClosedBar(oneHourBars, entryTime, 60);
		if (oneHour != null && oneHour.ema20 > 0.0 && oneHour.ema9 > 0.0) {
			if (oneHour.close > oneHour.ema20 || oneHour.ema9 > oneHour.ema20) {
				return false;
			}
		}

		return true;
	}

	private static Bar latestClosedBar(List<Bar> bars, LocalTime entryTime, int timeframeMinutes) {
		if (bars == null || bars.isEmpty() || entryTime == null) {
			return null;
		}
		Bar latest = null;
		for (int index = 0; index < bars.size(); index++) {
			Bar bar = bars.get(index);
			LocalTime closeTime = bar.marketTime.plusMinutes(timeframeMinutes);
			if (!closeTime.isAfter(entryTime)) {
				latest = bar;
			}
		}
		return latest;
	}

	private static double highest(List<Bar> bars) {
		double value = Double.NEGATIVE_INFINITY;
		for (int index = 0; index < bars.size(); index++) {
			value = Math.max(value, bars.get(index).high);
		}
		return value == Double.NEGATIVE_INFINITY ? 0.0 : value;
	}

	private static double lowest(List<Bar> bars) {
		double value = Double.POSITIVE_INFINITY;
		for (int index = 0; index < bars.size(); index++) {
			value = Math.min(value, bars.get(index).low);
		}
		return value == Double.POSITIVE_INFINITY ? 0.0 : value;
	}

	private static double recentSwingLow(List<Bar> bars, int endIndex, int lookbackBars) {
		if (bars == null || bars.isEmpty()) {
			return 0.0;
		}
		int start = Math.max(0, endIndex - Math.max(1, lookbackBars));
		int end = Math.min(endIndex, bars.size() - 1);
		double value = Double.POSITIVE_INFINITY;
		for (int index = start; index <= end; index++) {
			value = Math.min(value, bars.get(index).low);
		}
		return value == Double.POSITIVE_INFINITY ? bars.get(end).low : value;
	}

	private static double recentSwingHigh(List<Bar> bars, int endIndex, int lookbackBars) {
		if (bars == null || bars.isEmpty()) {
			return 0.0;
		}
		int start = Math.max(0, endIndex - Math.max(1, lookbackBars));
		int end = Math.min(endIndex, bars.size() - 1);
		double value = Double.NEGATIVE_INFINITY;
		for (int index = start; index <= end; index++) {
			value = Math.max(value, bars.get(index).high);
		}
		return value == Double.NEGATIVE_INFINITY ? bars.get(end).high : value;
	}

	private static double compressedLongStop(InstrumentSpec spec, List<Bar> bars, int index, double entryPrice, double maxRiskTicks) {
		double swingStop = recentSwingLow(bars, index, 5) - (spec.tickSize * 2.0);
		double cappedStop = entryPrice - (Math.max(1.0, maxRiskTicks) * spec.tickSize);
		double stop = Math.max(swingStop, cappedStop);
		double minRiskStop = entryPrice - (spec.tickSize * 4.0);
		return roundToTick(spec, Math.min(stop, minRiskStop));
	}

	private static double compressedShortStop(InstrumentSpec spec, List<Bar> bars, int index, double entryPrice, double maxRiskTicks) {
		double swingStop = recentSwingHigh(bars, index, 5) + (spec.tickSize * 2.0);
		double cappedStop = entryPrice + (Math.max(1.0, maxRiskTicks) * spec.tickSize);
		double stop = Math.min(swingStop, cappedStop);
		double minRiskStop = entryPrice + (spec.tickSize * 4.0);
		return roundToTick(spec, Math.max(stop, minRiskStop));
	}

	private static double adaptiveRewardMultiple(InstrumentSpec spec, List<Bar> bars, Signal signal, int executionIndex, double baseRewardMultiple, FuturesStrategySettings settings) {
		FuturesStrategySettings safeSettings = settings == null ? defaultFuturesStrategySettings() : settings;
		if (!safeSettings.enableAdaptiveExits || bars == null || bars.isEmpty() || signal == null) {
			return baseRewardMultiple;
		}
		int signalIndex = Math.max(0, Math.min(signal.entryIndex, bars.size() - 1));
		int entryIndex = Math.max(0, Math.min(executionIndex, bars.size() - 1));
		Bar signalBar = bars.get(signalIndex);
		Bar entryBar = bars.get(entryIndex);
		double rewardMultiple = baseRewardMultiple;
		double volumeRatio = signalBar.volumeSma20 <= 0.0 ? 1.0 : signalBar.volume / signalBar.volumeSma20;
		if (volumeRatio >= safeSettings.adaptiveMinVolumeRatio) {
			rewardMultiple += safeSettings.adaptiveVolumeTargetBoost;
		}
		if (signalBar.bodyPct >= safeSettings.adaptiveMinBodyPct) {
			rewardMultiple += safeSettings.adaptiveBodyTargetBoost;
		}
		if (emaTrendAligned(signal.side, entryBar)) {
			rewardMultiple += safeSettings.adaptiveTrendTargetBoost;
		}
		return clamp(rewardMultiple, Math.max(0.5, baseRewardMultiple), safeSettings.adaptiveMaxRewardRisk);
	}

	private static boolean emaTrendAligned(String side, Bar bar) {
		if (bar == null) {
			return false;
		}
		if ("LONG".equals(side)) {
			return bar.close >= bar.ema20 && bar.ema9 >= bar.ema20;
		}
		return bar.close <= bar.ema20 && bar.ema9 <= bar.ema20;
	}

	private static boolean shouldEarlyLossCut(InstrumentSpec spec, FuturesStrategySettings settings, String side, double entryPrice, double initialRisk, Bar bar, int contracts, int heldBars) {
		FuturesStrategySettings safeSettings = settings == null ? defaultFuturesStrategySettings() : settings;
		if (!safeSettings.enableEarlyLossCut || heldBars < safeSettings.earlyLossCutBars || initialRisk <= 0.0 || bar == null) {
			return false;
		}
		double adverseMove = "LONG".equals(side) ? entryPrice - bar.close : bar.close - entryPrice;
		double favorableMove = "LONG".equals(side) ? bar.high - entryPrice : entryPrice - bar.low;
		if (adverseMove < initialRisk * safeSettings.earlyLossCutR) {
			return false;
		}
		return favorableMove < initialRisk * safeSettings.earlyLossCutMinFavorableR;
	}

	private static double applySlippage(InstrumentSpec spec, double price, String side, double slippageTicks, boolean entry) {
		double slippage = slippageTicks * spec.tickSize;
		if ("LONG".equals(side)) {
			return roundToTick(spec, entry ? price + slippage : price - slippage);
		}
		return roundToTick(spec, entry ? price - slippage : price + slippage);
	}

	private static double updateManagedStop(InstrumentSpec spec, String side, double entryPrice, double activeStop, double closePrice, double initialRisk, double trailDistance) {
		if (initialRisk <= 0.0) {
			return activeStop;
		}
		double favorableMove = "LONG".equals(side) ? closePrice - entryPrice : entryPrice - closePrice;
		if (favorableMove < initialRisk * 0.75) {
			return activeStop;
		}
		if ("LONG".equals(side)) {
			double breakeven = entryPrice + spec.tickSize;
			double trailed = favorableMove >= initialRisk * 1.15 ? closePrice - trailDistance : breakeven;
			return roundToTick(spec, Math.max(activeStop, trailed));
		}
		double breakeven = entryPrice - spec.tickSize;
		double trailed = favorableMove >= initialRisk * 1.15 ? closePrice + trailDistance : breakeven;
		return roundToTick(spec, Math.min(activeStop, trailed));
	}

	private static double favorablePnl(InstrumentSpec spec, String side, double entry, Bar bar, int contracts) {
		double price = "LONG".equals(side) ? bar.high : bar.low;
		return pnlForPrice(spec, side, entry, price, contracts);
	}

	private static double adversePnl(InstrumentSpec spec, String side, double entry, Bar bar, int contracts) {
		double price = "LONG".equals(side) ? bar.low : bar.high;
		return pnlForPrice(spec, side, entry, price, contracts);
	}

	private static double pnlForPrice(InstrumentSpec spec, String side, double entry, double exit, int contracts) {
		double ticks = "LONG".equals(side) ? (exit - entry) / spec.tickSize : (entry - exit) / spec.tickSize;
		return round(ticks * spec.tickValue * contracts);
	}

	private static double roundToTick(InstrumentSpec spec, double price) {
		if (spec.tickSize <= 0.0) {
			return round(price);
		}
		return round(Math.round(price / spec.tickSize) * spec.tickSize);
	}

	private static double parseDouble(String value) {
		try {
			return Double.parseDouble(value.trim());
		} catch (Exception e) {
			return 0.0;
		}
	}

	private static double parseDouble(String value, double defaultValue) {
		try {
			if (value == null || value.trim().isEmpty()) {
				return defaultValue;
			}
			return Double.parseDouble(value.trim());
		} catch (Exception e) {
			return defaultValue;
		}
	}

	private static int parseInt(String value, int defaultValue) {
		try {
			if (value == null || value.trim().isEmpty()) {
				return defaultValue;
			}
			return Integer.parseInt(value.trim());
		} catch (Exception e) {
			return defaultValue;
		}
	}

	private static boolean parseBoolean(String value, boolean defaultValue) {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		String normalized = value.trim().toLowerCase();
		if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
			return true;
		}
		if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
			return false;
		}
		return defaultValue;
	}

	private static LocalDate parseDate(String value, LocalDate defaultDate) {
		try {
			if (value == null || value.trim().isEmpty()) {
				return defaultDate;
			}
			return LocalDate.parse(value.trim());
		} catch (Exception e) {
			return defaultDate;
		}
	}

	private static double positiveOrDefault(double value, double defaultValue) {
		return value > 0.0 ? value : defaultValue;
	}

	private static int boundedInt(int value, int defaultValue, int min, int max) {
		int candidate = value > 0 ? value : defaultValue;
		return Math.max(min, Math.min(max, candidate));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String cleanOrDefault(String value, String defaultValue) {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		return value.trim();
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			if (value != null && !value.trim().isEmpty()) {
				return value.trim();
			}
		}
		return "";
	}

	private static String jsonObjectOrDefault(String value, String defaultValue) {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		String trimmed = value.trim();
		if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
			return trimmed;
		}
		return defaultValue;
	}

	private static double jsonNumber(String json, String key, double defaultValue) {
		if (json == null || key == null || key.trim().isEmpty()) {
			return defaultValue;
		}
		String needle = "\"" + key + "\":";
		int start = json.indexOf(needle);
		if (start < 0) {
			return defaultValue;
		}
		int index = start + needle.length();
		while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
			index++;
		}
		int end = index;
		while (end < json.length()) {
			char ch = json.charAt(end);
			if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.' || ch == 'E' || ch == 'e') {
				end++;
			} else {
				break;
			}
		}
		if (end <= index) {
			return defaultValue;
		}
		return parseDouble(json.substring(index, end), defaultValue);
	}

	private static double jsonFirstNumber(String json, String[] keys, double defaultValue) {
		if (keys == null) {
			return defaultValue;
		}
		for (int index = 0; index < keys.length; index++) {
			double value = jsonNumberFlexible(json, keys[index], Double.NaN);
			if (!Double.isNaN(value)) {
				return value;
			}
		}
		return defaultValue;
	}

	private static double jsonNumberFlexible(String json, String key, double defaultValue) {
		if (json == null || key == null || key.trim().isEmpty()) {
			return defaultValue;
		}
		String needle = "\"" + key + "\":";
		int start = json.indexOf(needle);
		if (start < 0) {
			return defaultValue;
		}
		int index = start + needle.length();
		while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
			index++;
		}
		if (index < json.length() && json.charAt(index) == '"') {
			int endQuote = json.indexOf('"', index + 1);
			if (endQuote > index) {
				return parseDouble(json.substring(index + 1, endQuote), defaultValue);
			}
			return defaultValue;
		}
		int end = index;
		while (end < json.length()) {
			char ch = json.charAt(end);
			if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.' || ch == 'E' || ch == 'e') {
				end++;
			} else {
				break;
			}
		}
		if (end <= index) {
			return defaultValue;
		}
		return parseDouble(json.substring(index, end), defaultValue);
	}

	private static String jsonText(String json, String key, String defaultValue) {
		if (json == null || key == null || key.trim().isEmpty()) {
			return defaultValue;
		}
		String needle = "\"" + key + "\":";
		int start = json.indexOf(needle);
		if (start < 0) {
			return defaultValue;
		}
		int index = start + needle.length();
		while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
			index++;
		}
		if (index >= json.length() || json.charAt(index) != '"') {
			return defaultValue;
		}
		StringBuilder value = new StringBuilder();
		boolean escaped = false;
		for (int cursor = index + 1; cursor < json.length(); cursor++) {
			char ch = json.charAt(cursor);
			if (escaped) {
				if (ch == 'n') {
					value.append('\n');
				} else if (ch == 'r') {
					value.append('\r');
				} else {
					value.append(ch);
				}
				escaped = false;
			} else if (ch == '\\') {
				escaped = true;
			} else if (ch == '"') {
				return value.toString();
			} else {
				value.append(ch);
			}
		}
		return defaultValue;
	}

	private static boolean jsonBoolean(String json, String key) {
		if (json == null || key == null || key.trim().isEmpty()) {
			return false;
		}
		String needle = "\"" + key + "\":";
		int start = json.indexOf(needle);
		if (start < 0) {
			return false;
		}
		int index = start + needle.length();
		while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
			index++;
		}
		return index + 4 <= json.length() && "true".equalsIgnoreCase(json.substring(index, index + 4));
	}

	private static boolean usesEndOfDayTrailing(String trailingDrawdownMode) {
		return "END_OF_DAY".equalsIgnoreCase(trailingDrawdownMode == null ? "" : trailingDrawdownMode);
	}

	private static double endOfDayTrailingThreshold(double currentThreshold, double referenceBalance, double accountSize, double maxTrailingDrawdown) {
		double nextThreshold = Math.max(currentThreshold, referenceBalance - Math.abs(maxTrailingDrawdown));
		return round(Math.min(accountSize, nextThreshold));
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

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
