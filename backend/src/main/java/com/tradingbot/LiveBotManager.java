package com.tradingbot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LiveBotManager {

	private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 30);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(16, 0);
	private static final LocalTime SESSION_EXIT_TIME = LocalTime.of(15, 59);
	private static final DateTimeFormatter DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final int TREND_LOOKBACK_DAYS = 10;

	public static class ActiveTrade {
		public int id;
		public String strategyCode;
		public String strategyName;
		public String side;
		public double qty;
		public double entryPrice;
		public double stopPrice;
		public double targetPrice;
		public double effectiveStopPrice;
		public double effectiveTargetPrice;
		public double currentPrice;
		public double unrealizedPnl;
		public String openedAt;
		public String tradeNotes;
	}

	public static class TradeLog {
		public int id;
		public String strategyCode;
		public String strategyName;
		public String symbol;
		public String time;
		public String closedAt;
		public String side;
		public double qty;
		public Double entry;
		public Double exit;
		public Double pnl;
		public String status;
		public String tradeNotes;
	}

	public static class MarketDataPoint {
		public String time;
		public double open;
		public double high;
		public double low;
		public double close;
		public double volume;
		public int tradeCount;
		public double vwap;
		public double sma9;
		public double sma20;
		public double ema9;
		public double ema20;
		public double rsi14;
		public double atr14;
		public double volumeAverage20;
		public double volumeRatio;
	}

	public static class EquityPoint {
		public String time;
		public double equity;
		public double cash;
		public double realizedPnl;
		public double unrealizedPnl;
		public double totalPnl;
	}

	public static class LiveBotStatus {
		public boolean success;
		public boolean requiresConfirmation;
		public boolean running;
		public String message;
		public String symbol;
		public double perTradeBuyingPower;
		public double takeProfit;
		public double lossLimit;
		public double cash;
		public double accountEquity;
		public double startingEquity;
		public double grossPnl;
		public double realizedPnl;
		public double unrealizedPnl;
		public double totalReturn;
		public double winRate;
		public double drawdown;
		public double profitFactor;
		public int trades;
		public int closedTrades;
		public int pullCount;
		public int liveBotId;
		public String startedAt;
		public String lastPulledAt;
		public String nextPullAt;
		public String latestBarTime;
		public double latestOpen;
		public double latestHigh;
		public double latestLow;
		public double latestPrice;
		public double latestVolume;
		public double latestVwap;
		public double latestSma9;
		public double latestSma20;
		public double latestEma9;
		public double latestEma20;
		public double latestRsi14;
		public double latestAtr14;
		public double latestVolumeAverage20;
		public double latestVolumeRatio;
		public double orbHigh;
		public double orbLow;
		public String lastDecision;
		public ActiveTrade activeTrade;
		public List<ActiveTrade> activeTrades = new ArrayList<ActiveTrade>();
		public List<String> enabledStrategies = new ArrayList<String>();
		public List<TradeLog> tradeLogs = new ArrayList<TradeLog>();
		public List<MarketDataPoint> oneMinuteData = new ArrayList<MarketDataPoint>();
		public List<MarketDataPoint> fiveMinuteData = new ArrayList<MarketDataPoint>();
		public List<MarketDataPoint> thirtyMinuteData = new ArrayList<MarketDataPoint>();
		public List<MarketDataPoint> oneHourData = new ArrayList<MarketDataPoint>();
		public List<EquityPoint> equityCurve = new ArrayList<EquityPoint>();
	}

	private static class ActiveTradeState {
		private int id;
		private String strategyCode;
		private String strategyName;
		private String side;
		private double qty;
		private double entryPrice;
		private double stopPrice;
		private double targetPrice;
		private double effectiveStopPrice;
		private double effectiveTargetPrice;
		private double currentPrice;
		private String openedAt;
		private String tradeNotes;
	}

	private static class PositionPlan {
		private double qty;
		private double effectiveStopPrice;
		private double effectiveTargetPrice;
	}

	private static class CloseDecision {
		private final double exitPrice;
		private final String closedAt;
		private final LocalDate tradingDay;
		private final LocalTime closedTime;
		private final String reason;

		private CloseDecision(double exitPrice, String closedAt, LocalDate tradingDay, LocalTime closedTime, String reason) {
			this.exitPrice = exitPrice;
			this.closedAt = closedAt;
			this.tradingDay = tradingDay;
			this.closedTime = closedTime;
			this.reason = reason;
		}
	}

	private static class TradeCloseRequest {
		private final ActiveTradeState trade;
		private final CloseDecision decision;

		private TradeCloseRequest(ActiveTradeState trade, CloseDecision decision) {
			this.trade = trade;
			this.decision = decision;
		}
	}

	private static class Session {
		private final String email;
		private String symbol;
		private double perTradeBuyingPower;
		private double takeProfit;
		private double lossLimit;
		private boolean running;
		private int liveBotId;
		private double cash;
		private double accountEquity;
		private double startingEquity;
		private double realizedPnl;
		private double unrealizedPnl;
		private double totalReturn;
		private double winRate;
		private double drawdown;
		private double profitFactor;
		private double peakEquity;
		private double grossProfit;
		private double grossLoss;
		private int closedTradeCount;
		private int winningTrades;
		private int pullCount;
		private String startedAt;
		private String lastPulledAt;
		private String nextPullAt;
		private String latestBarTime;
		private double latestOpen;
		private double latestHigh;
		private double latestLow;
		private double latestPrice;
		private double latestVolume;
		private double latestVwap;
		private double latestSma9;
		private double latestSma20;
		private double latestEma9;
		private double latestEma20;
		private double latestRsi14;
		private double latestAtr14;
		private double latestVolumeAverage20;
		private double latestVolumeRatio;
		private double orbHigh;
		private double orbLow;
		private String lastDecision;
		private ActiveTradeState activeTrade;
		private List<ActiveTradeState> activeTrades = new ArrayList<ActiveTradeState>();
		private List<String> enabledStrategies = new ArrayList<String>();
		private List<TradeLog> closedTrades = new ArrayList<TradeLog>();
		private List<MarketDataPoint> oneMinuteData = new ArrayList<MarketDataPoint>();
		private List<MarketDataPoint> fiveMinuteData = new ArrayList<MarketDataPoint>();
		private List<MarketDataPoint> thirtyMinuteData = new ArrayList<MarketDataPoint>();
		private List<MarketDataPoint> oneHourData = new ArrayList<MarketDataPoint>();
		private List<EquityPoint> equityCurve = new ArrayList<EquityPoint>();
		private Set<String> executedSignals = new HashSet<String>();
		private Map<LocalDate, Integer> orbTradesTakenByDay = new HashMap<LocalDate, Integer>();
		private Map<LocalDate, Integer> ifvgTradesTakenByDay = new HashMap<LocalDate, Integer>();
		private Map<LocalDate, Integer> vwapTradesTakenByDay = new HashMap<LocalDate, Integer>();
		private Map<LocalDate, Integer> vwapMeanReversionTradesTakenByDay = new HashMap<LocalDate, Integer>();
		private Map<LocalDate, Integer> gapGoTradesTakenByDay = new HashMap<LocalDate, Integer>();
		private ScheduledExecutorService executor;

		private Session(String email) {
			this.email = email;
		}
	}

	private final AccountManager accountManager;
	private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();

	public LiveBotManager(AccountManager accountManager) {
		this.accountManager = accountManager;
	}

	public LiveBotStatus startBot(
		String email,
		String symbol,
		double perTradeBuyingPower,
		double takeProfit,
		double lossLimit
	) {
		if (isBlank(email)) {
			return buildIdleStatus(false, false, "Missing account email.");
		}

		Session existingSession = sessions.get(email);
		if (existingSession != null) {
			synchronized (existingSession) {
				if (existingSession.running) {
					return buildStatus(existingSession, true, false, "Live bot already running.");
				}
				shutdownExecutor(existingSession);
			}
		}

		Session session = new Session(email);
		StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();
		AlpacaManager alpacaManager = createAlpacaManager(email);

		if (alpacaManager == null) {
			return buildIdleStatus(false, false, "Broker keys not configured.");
		}

		AlpacaManager.AccountSnapshot snapshot = alpacaManager.getAccountSnapshot();
		double startingEquity = deriveStartingEquity(snapshot);
		if (startingEquity <= 0.0) {
			return buildIdleStatus(false, false, "Unable to read account equity from Alpaca.");
		}

		synchronized (session) {
			session.symbol = AlpacaManager.normalizeSymbol(symbol);
			session.perTradeBuyingPower = positiveOrDefault(perTradeBuyingPower, 0.0);
			session.takeProfit = positiveOrDefault(takeProfit, 0.0);
			session.lossLimit = positiveOrDefault(lossLimit, 0.0);
			session.running = true;
			session.startedAt = formatDisplayTime(ZonedDateTime.now(MARKET_ZONE));
			session.lastDecision = "Live bot started. Pulling the first Alpaca update.";
			session.startingEquity = roundToTwoDecimals(startingEquity);
			session.peakEquity = session.startingEquity;
			session.cash = roundToTwoDecimals(snapshot.cash);
			session.accountEquity = roundToTwoDecimals(valueOrDefault(snapshot.equity, session.startingEquity));
			session.enabledStrategies = enabledStrategies(settings);
			session.liveBotId = DatabaseManager.createLiveBotRun(
				accountManager == null ? -1 : accountManager.getAccountId(email),
				session.symbol,
				session.startedAt,
				session.startingEquity
			);
		}

		sessions.put(email, session);
		runLiveCycle(session);
		scheduleSession(session);
		return buildStatus(session, true, false, "Live bot is running.");
	}

	public LiveBotStatus stopBot(String email, boolean force) {
		if (isBlank(email)) {
			return buildIdleStatus(false, false, "Missing account email.");
		}

		Session session = sessions.get(email);
		if (session == null) {
			return buildIdleStatus(true, false, "Live bot is already stopped.");
		}

		synchronized (session) {
			if (!session.running) {
				return buildStatus(session, true, false, "Live bot is already stopped.");
			}
			if (activeTradeCount(session) > 0 && !force) {
				return buildStatus(
					session,
					true,
					true,
					"Are you sure you want to stop? There are active trades in progress."
				);
			}
		}

		if (force) {
			AlpacaManager alpacaManager = createAlpacaManager(email);
			if (alpacaManager == null) {
				return buildStatus(session, false, false, "Broker keys not configured.");
			}

			List<TradeCloseRequest> manualCloses = new ArrayList<TradeCloseRequest>();
			synchronized (session) {
				ZonedDateTime now = ZonedDateTime.now(MARKET_ZONE);
				for (int index = 0; index < session.activeTrades.size(); index++) {
					ActiveTradeState trade = session.activeTrades.get(index);
					CloseDecision manualClose = new CloseDecision(
						roundToTwoDecimals(trade.currentPrice > 0.0 ? trade.currentPrice : trade.entryPrice),
						formatDisplayTime(now),
						now.toLocalDate(),
						now.toLocalTime(),
						"Manual stop exit."
					);
					manualCloses.add(new TradeCloseRequest(trade, manualClose));
				}
			}

			for (int index = 0; index < manualCloses.size(); index++) {
				if (!closeTrade(alpacaManager, session, manualCloses.get(index).trade, manualCloses.get(index).decision)) {
					return buildStatus(session, false, false, "Failed to close every active trade. Live bot is still running.");
				}
			}
		}

		synchronized (session) {
			session.running = false;
			session.nextPullAt = "";
			session.lastDecision = "Live bot stopped.";
			shutdownExecutor(session);
			refreshDerivedMetrics(session);
			persistSession(session, "STOPPED");
		}

		return buildStatus(session, true, false, "Live bot stopped.");
	}

	public LiveBotStatus getStatus(String email) {
		if (isBlank(email)) {
			return buildIdleStatus(false, false, "Missing account email.");
		}

		Session session = sessions.get(email);
		if (session == null) {
			return buildIdleStatus(true, false, "Live bot is idle.");
		}

		return buildStatus(session, true, false, "");
	}

	public String statusToJson(LiveBotStatus status) {
		LiveBotStatus safeStatus = status == null ? buildIdleStatus(false, false, "Live bot unavailable.") : status;
		StringBuilder json = new StringBuilder("{");
		json.append("\"success\":").append(safeStatus.success).append(",");
		json.append("\"requiresConfirmation\":").append(safeStatus.requiresConfirmation).append(",");
		json.append("\"running\":").append(safeStatus.running).append(",");
		json.append("\"message\":").append(jsonString(safeStatus.message)).append(",");
		json.append("\"symbol\":").append(jsonString(safeStatus.symbol)).append(",");
		json.append("\"perTradeBuyingPower\":").append(roundToTwoDecimals(safeStatus.perTradeBuyingPower)).append(",");
		json.append("\"takeProfit\":").append(roundToTwoDecimals(safeStatus.takeProfit)).append(",");
		json.append("\"lossLimit\":").append(roundToTwoDecimals(safeStatus.lossLimit)).append(",");
		json.append("\"cash\":").append(roundToTwoDecimals(safeStatus.cash)).append(",");
		json.append("\"accountEquity\":").append(roundToTwoDecimals(safeStatus.accountEquity)).append(",");
		json.append("\"startingEquity\":").append(roundToTwoDecimals(safeStatus.startingEquity)).append(",");
		json.append("\"grossPnl\":").append(roundToTwoDecimals(safeStatus.grossPnl)).append(",");
		json.append("\"realizedPnl\":").append(roundToTwoDecimals(safeStatus.realizedPnl)).append(",");
		json.append("\"unrealizedPnl\":").append(roundToTwoDecimals(safeStatus.unrealizedPnl)).append(",");
		json.append("\"totalReturn\":").append(roundToTwoDecimals(safeStatus.totalReturn)).append(",");
		json.append("\"winRate\":").append(roundToTwoDecimals(safeStatus.winRate)).append(",");
		json.append("\"drawdown\":").append(roundToTwoDecimals(safeStatus.drawdown)).append(",");
		json.append("\"profitFactor\":").append(roundToTwoDecimals(safeStatus.profitFactor)).append(",");
		json.append("\"trades\":").append(safeStatus.trades).append(",");
		json.append("\"closedTrades\":").append(safeStatus.closedTrades).append(",");
		json.append("\"pullCount\":").append(safeStatus.pullCount).append(",");
		json.append("\"liveBotId\":").append(safeStatus.liveBotId).append(",");
		json.append("\"startedAt\":").append(jsonString(safeStatus.startedAt)).append(",");
		json.append("\"lastPulledAt\":").append(jsonString(safeStatus.lastPulledAt)).append(",");
		json.append("\"nextPullAt\":").append(jsonString(safeStatus.nextPullAt)).append(",");
		json.append("\"latestBarTime\":").append(jsonString(safeStatus.latestBarTime)).append(",");
		json.append("\"latestOpen\":").append(roundToTwoDecimals(safeStatus.latestOpen)).append(",");
		json.append("\"latestHigh\":").append(roundToTwoDecimals(safeStatus.latestHigh)).append(",");
		json.append("\"latestLow\":").append(roundToTwoDecimals(safeStatus.latestLow)).append(",");
		json.append("\"latestPrice\":").append(roundToTwoDecimals(safeStatus.latestPrice)).append(",");
		json.append("\"latestVolume\":").append(roundToTwoDecimals(safeStatus.latestVolume)).append(",");
		json.append("\"latestVwap\":").append(roundToTwoDecimals(safeStatus.latestVwap)).append(",");
		json.append("\"latestSma9\":").append(roundToTwoDecimals(safeStatus.latestSma9)).append(",");
		json.append("\"latestSma20\":").append(roundToTwoDecimals(safeStatus.latestSma20)).append(",");
		json.append("\"latestEma9\":").append(roundToTwoDecimals(safeStatus.latestEma9)).append(",");
		json.append("\"latestEma20\":").append(roundToTwoDecimals(safeStatus.latestEma20)).append(",");
		json.append("\"latestRsi14\":").append(roundToTwoDecimals(safeStatus.latestRsi14)).append(",");
		json.append("\"latestAtr14\":").append(roundToTwoDecimals(safeStatus.latestAtr14)).append(",");
		json.append("\"latestVolumeAverage20\":").append(roundToTwoDecimals(safeStatus.latestVolumeAverage20)).append(",");
		json.append("\"latestVolumeRatio\":").append(roundToTwoDecimals(safeStatus.latestVolumeRatio)).append(",");
		json.append("\"orbHigh\":").append(roundToTwoDecimals(safeStatus.orbHigh)).append(",");
		json.append("\"orbLow\":").append(roundToTwoDecimals(safeStatus.orbLow)).append(",");
		json.append("\"lastDecision\":").append(jsonString(safeStatus.lastDecision)).append(",");
		json.append("\"enabledStrategies\":").append(stringListToJson(safeStatus.enabledStrategies)).append(",");
		json.append("\"activeTrade\":").append(activeTradeToJson(safeStatus.activeTrade)).append(",");
		json.append("\"activeTrades\":").append(activeTradesToJson(safeStatus.activeTrades)).append(",");
		json.append("\"tradeLogs\":").append(tradeLogsToJson(safeStatus.tradeLogs)).append(",");
		json.append("\"marketData\":").append(marketDataToJson(safeStatus)).append(",");
		json.append("\"equityCurve\":").append(equityPointsToJson(safeStatus.equityCurve));
		json.append("}");
		return json.toString();
	}

	private void scheduleSession(final Session session) {
		synchronized (session) {
			shutdownExecutor(session);
			if (!session.running) {
				return;
			}
			session.executor = Executors.newSingleThreadScheduledExecutor();
			session.executor.scheduleAtFixedRate(new Runnable() {
				@Override
				public void run() {
					runLiveCycle(session);
				}
			}, 60, 60, TimeUnit.SECONDS);
		}
	}

	private void shutdownExecutor(Session session) {
		if (session.executor != null) {
			session.executor.shutdownNow();
			session.executor = null;
		}
	}

	private void runLiveCycle(Session session) {
		String email;
		String symbol;
		double takeProfit;
		double lossLimit;
		StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();
		List<String> enabledStrategies = enabledStrategies(settings);

		synchronized (session) {
			if (!session.running) {
				return;
			}
			email = session.email;
			symbol = session.symbol;
			takeProfit = session.takeProfit;
			lossLimit = session.lossLimit;
		}

		AlpacaManager alpacaManager = createAlpacaManager(email);
		if (alpacaManager == null) {
			synchronized (session) {
				session.lastDecision = "Broker keys not configured.";
				persistSession(session, session.running ? "RUNNING" : "STOPPED");
			}
			return;
		}

		AlpacaManager.AccountSnapshot accountSnapshot = alpacaManager.getAccountSnapshot();
		ZonedDateTime now = ZonedDateTime.now(MARKET_ZONE);
		ZonedDateTime sessionStart = now.toLocalDate().atTime(MARKET_OPEN_TIME).atZone(MARKET_ZONE);
		ZonedDateTime trendStart = now.toLocalDate().minusDays(TREND_LOOKBACK_DAYS).atStartOfDay(MARKET_ZONE);
		List<AlpacaManager.CachedBar> oneMinuteBars = alpacaManager.fetchBars(symbol, sessionStart, now, "1Min");
		List<AlpacaManager.CachedBar> fiveMinuteBars = alpacaManager.fetchBars(symbol, sessionStart, now, "5Min");
		List<AlpacaManager.CachedBar> thirtyMinuteBars = alpacaManager.fetchBars(symbol, trendStart, now, "30Min");
		List<AlpacaManager.CachedBar> oneHourBars = alpacaManager.fetchBars(symbol, trendStart, now, "1Hour");
		List<MarketDataPoint> oneMinuteData = buildMarketDataPoints(oneMinuteBars, 120);
		List<MarketDataPoint> fiveMinuteData = buildMarketDataPoints(fiveMinuteBars, 90);
		List<MarketDataPoint> thirtyMinuteData = buildMarketDataPoints(thirtyMinuteBars, 120);
		List<MarketDataPoint> oneHourData = buildMarketDataPoints(oneHourBars, 120);
		int orbWindowMinutes = settings != null && settings.orb != null ? settings.orb.orbWindowMinutes : 15;
		double calculatedOrbHigh = calculateOpeningRangeHigh(oneMinuteBars, orbWindowMinutes);
		double calculatedOrbLow = calculateOpeningRangeLow(oneMinuteBars, orbWindowMinutes);

		synchronized (session) {
			if (!session.running) {
				return;
			}
			session.enabledStrategies = enabledStrategies;
			session.pullCount++;
			session.lastPulledAt = formatDisplayTime(now);
			session.nextPullAt = formatDisplayTime(now.plusMinutes(1));
			session.cash = roundToTwoDecimals(accountSnapshot.cash);
			session.accountEquity = roundToTwoDecimals(valueOrDefault(accountSnapshot.equity, session.startingEquity + session.realizedPnl + session.unrealizedPnl));
			session.oneMinuteData = oneMinuteData;
			session.fiveMinuteData = fiveMinuteData;
			session.thirtyMinuteData = thirtyMinuteData;
			session.oneHourData = oneHourData;
			session.orbHigh = calculatedOrbHigh;
			session.orbLow = calculatedOrbLow;
		}

		if (oneMinuteBars.isEmpty()) {
			synchronized (session) {
				session.latestBarTime = "";
				session.latestOpen = 0.0;
				session.latestHigh = 0.0;
				session.latestLow = 0.0;
				session.latestPrice = 0.0;
				session.latestVolume = 0.0;
				session.latestVwap = 0.0;
				session.latestSma9 = 0.0;
				session.latestSma20 = 0.0;
				session.latestEma9 = 0.0;
				session.latestEma20 = 0.0;
				session.latestRsi14 = 0.0;
				session.latestAtr14 = 0.0;
				session.latestVolumeAverage20 = 0.0;
				session.latestVolumeRatio = 0.0;
				recalculateUnrealizedPnl(session);
				syncPrimaryActiveTrade(session);
				session.lastDecision = "Waiting for market data from Alpaca.";
				refreshDerivedMetrics(session);
				appendEquityPoint(session, now);
				persistSession(session, session.running ? "RUNNING" : "STOPPED");
			}
			return;
		}

		AlpacaManager.CachedBar latestBar = oneMinuteBars.get(oneMinuteBars.size() - 1);
		MarketDataPoint latestPoint = oneMinuteData.isEmpty() ? null : oneMinuteData.get(oneMinuteData.size() - 1);
		synchronized (session) {
			session.latestBarTime = latestBar.displayTime;
			session.latestOpen = roundToTwoDecimals(latestBar.open);
			session.latestHigh = roundToTwoDecimals(latestBar.high);
			session.latestLow = roundToTwoDecimals(latestBar.low);
			session.latestPrice = roundToTwoDecimals(latestBar.close);
			session.latestVolume = roundToTwoDecimals(latestBar.volume);
			if (latestPoint != null) {
				session.latestVwap = roundToTwoDecimals(latestPoint.vwap);
				session.latestSma9 = roundToTwoDecimals(latestPoint.sma9);
				session.latestSma20 = roundToTwoDecimals(latestPoint.sma20);
				session.latestEma9 = roundToTwoDecimals(latestPoint.ema9);
				session.latestEma20 = roundToTwoDecimals(latestPoint.ema20);
				session.latestRsi14 = roundToTwoDecimals(latestPoint.rsi14);
				session.latestAtr14 = roundToTwoDecimals(latestPoint.atr14);
				session.latestVolumeAverage20 = roundToTwoDecimals(latestPoint.volumeAverage20);
				session.latestVolumeRatio = roundToTwoDecimals(latestPoint.volumeRatio);
			}
			for (int index = 0; index < session.activeTrades.size(); index++) {
				session.activeTrades.get(index).currentPrice = roundToTwoDecimals(latestBar.close);
			}
			recalculateUnrealizedPnl(session);
			syncPrimaryActiveTrade(session);
		}

		List<TradeCloseRequest> closeRequests = new ArrayList<TradeCloseRequest>();
		boolean tradeClosedThisCycle = false;
		synchronized (session) {
			for (int index = 0; index < session.activeTrades.size(); index++) {
				ActiveTradeState trade = session.activeTrades.get(index);
				CloseDecision closeDecision = resolveCloseDecision(trade, latestBar);
				if (closeDecision != null) {
					closeRequests.add(new TradeCloseRequest(trade, closeDecision));
				}
			}
		}
		for (int index = 0; index < closeRequests.size(); index++) {
			TradeCloseRequest closeRequest = closeRequests.get(index);
			boolean closeSucceeded = closeTrade(alpacaManager, session, closeRequest.trade, closeRequest.decision);
			if (!closeSucceeded) {
				synchronized (session) {
					refreshDerivedMetrics(session);
					persistSession(session, session.running ? "RUNNING" : "STOPPED");
				}
				return;
			}
			tradeClosedThisCycle = true;
		}

		if (enabledStrategies.isEmpty()) {
			synchronized (session) {
				session.lastDecision = "No enabled strategies on the Strategy page.";
			}
		} else {
			List<StrategyManager.LiveSignalSnapshot> liveSignals = StrategyManager.evaluateLiveSignals(
				settings,
				oneMinuteBars,
				fiveMinuteBars,
				thirtyMinuteBars,
				oneHourBars,
				latestBar.marketDate
			);

			List<StrategyManager.LiveSignalSnapshot> candidates;
			synchronized (session) {
				candidates = selectSignals(session, settings, liveSignals, latestBar.marketDate, latestBar.marketTime);
			}

			if (!candidates.isEmpty()) {
				double availableCapital = accountSnapshot.cash > 0.0
					? accountSnapshot.cash
					: valueOrDefault(session.accountEquity, session.startingEquity + session.realizedPnl + session.unrealizedPnl);
				synchronized (session) {
					availableCapital = roundToTwoDecimals(Math.max(0.0, availableCapital - reservedNotional(session.activeTrades)));
				}
				int openedCount = 0;
				for (int index = 0; index < candidates.size(); index++) {
					StrategyManager.LiveSignalSnapshot candidate = candidates.get(index);
					StrategyManager.StrategyConfig config = configForCode(settings, candidate.strategyCode);
					PositionPlan plan = buildPositionPlan(
						config,
						candidate,
						availableCapital,
						session.perTradeBuyingPower,
						takeProfit,
						lossLimit
					);

					if (plan == null || plan.qty < 1.0) {
						synchronized (session) {
							session.lastDecision = candidate.strategyName + " fired, but size rounded below 1 share.";
						}
						continue;
					}

					if (openTrade(alpacaManager, session, candidate, plan, latestBar.marketDate)) {
						openedCount++;
						availableCapital = roundToTwoDecimals(Math.max(0.0, availableCapital - (plan.qty * candidate.entryPrice)));
					}
				}
				if (openedCount == 0 && !tradeClosedThisCycle) {
					synchronized (session) {
						session.lastDecision = "Strategy signals fired, but no new orders were opened.";
					}
				}
			} else if (!tradeClosedThisCycle) {
				synchronized (session) {
					if (activeTradeCount(session) > 0) {
						session.lastDecision = "Active trades are open. Monitoring the latest minute bar.";
					} else {
						session.lastDecision = "No new strategy signal on the latest minute.";
					}
				}
			}
		}

		synchronized (session) {
			refreshDerivedMetrics(session);
			appendEquityPoint(session, now);
			persistSession(session, session.running ? "RUNNING" : "STOPPED");
		}
	}

	private List<StrategyManager.LiveSignalSnapshot> selectSignals(
		Session session,
		StrategyManager.StrategySettings settings,
		List<StrategyManager.LiveSignalSnapshot> signals,
		LocalDate tradingDay,
		LocalTime latestBarTime
	) {
		List<StrategyManager.LiveSignalSnapshot> selected = new ArrayList<StrategyManager.LiveSignalSnapshot>();
		if (signals == null || signals.isEmpty()) {
			return selected;
		}
		List<StrategyManager.LiveSignalSnapshot> executionSignals = new ArrayList<StrategyManager.LiveSignalSnapshot>();
		for (int signalIndex = 0; signalIndex < signals.size(); signalIndex++) {
			StrategyManager.LiveSignalSnapshot signal = signals.get(signalIndex);
			if (signal != null && signal.openedTime != null) {
				executionSignals.add(signal);
			}
		}
		Collections.sort(executionSignals, new Comparator<StrategyManager.LiveSignalSnapshot>() {
			@Override
			public int compare(StrategyManager.LiveSignalSnapshot first, StrategyManager.LiveSignalSnapshot second) {
				int timeCompare = first.openedTime.compareTo(second.openedTime);
				if (timeCompare != 0) {
					return timeCompare;
				}
				int sideCompare = safeString(first.side).compareTo(safeString(second.side));
				if (sideCompare != 0) {
					return sideCompare;
				}
				int scoreCompare = Double.compare(
					StrategyManager.liveSignalExecutionScore(second),
					StrategyManager.liveSignalExecutionScore(first)
				);
				if (scoreCompare != 0) {
					return scoreCompare;
				}
				return safeString(first.strategyCode).compareTo(safeString(second.strategyCode));
			}
		});

		String activeSide = activeSide(session);
		String selectedSide = "";
		Set<String> selectedBuckets = new HashSet<String>();
		int availableSlots = Math.max(0, StrategyManager.maxConcurrentTradesPerSymbol() - activeTradeCount(session));
		if (availableSlots <= 0) {
			return selected;
		}

		for (int index = 0; index < executionSignals.size(); index++) {
			StrategyManager.LiveSignalSnapshot signal = executionSignals.get(index);
			if (signal == null || signal.openedTime == null || !signal.openedTime.equals(latestBarTime)) {
				continue;
			}
			if (!isBlank(activeSide) && !activeSide.equals(signal.side)) {
				continue;
			}
			if (!isBlank(selectedSide) && !selectedSide.equals(signal.side)) {
				continue;
			}
			if (session.executedSignals.contains(signalKey(signal))) {
				continue;
			}
			if (selectedBuckets.contains(signalEntryBucket(signal))) {
				continue;
			}
			if (hasCrowdedActiveTrade(session.activeTrades, signal) || hasCrowdedSelectedSignal(selected, signal)) {
				continue;
			}

			StrategyManager.StrategyConfig config = configForCode(settings, signal.strategyCode);
			if (config == null || !config.isEnabled) {
				continue;
			}

			int tradesTaken = tradesTakenForDay(session, signal.strategyCode, tradingDay);
			if (tradesTaken >= config.maxTradesPerDay) {
				continue;
			}

			selected.add(signal);
			selectedBuckets.add(signalEntryBucket(signal));
			selectedSide = signal.side;
			if (selected.size() >= availableSlots) {
				break;
			}
		}

		return selected;
	}

	private PositionPlan buildPositionPlan(
		StrategyManager.StrategyConfig config,
		StrategyManager.LiveSignalSnapshot signal,
		double capital,
		double perTradeBuyingPower,
		double takeProfit,
		double lossLimit
	) {
		if (config == null || signal == null) {
			return null;
		}

		double perShareRisk = roundToTwoDecimals(Math.abs(signal.entryPrice - signal.stopPrice));
		if (perShareRisk <= 0.0) {
			return null;
		}

		double sizingCapital = positiveOrDefault(capital, 0.0);
		if (sizingCapital <= 0.0) {
			return null;
		}

		double riskBudget = roundToTwoDecimals(sizingCapital * (config.riskPerTradePct / 100.0));
		double qtyByRisk = Math.floor(riskBudget / perShareRisk);
		double buyingPowerCap = perTradeBuyingPower > 0.0 ? Math.min(sizingCapital, perTradeBuyingPower) : sizingCapital;
		double maxAffordableQty = Math.floor(buyingPowerCap / Math.max(1.0, signal.entryPrice));
		double qty = Math.floor(Math.min(qtyByRisk, maxAffordableQty));
		if (qty < 1.0) {
			return null;
		}

		PositionPlan plan = new PositionPlan();
		plan.qty = qty;
		plan.effectiveStopPrice = signal.stopPrice;
		plan.effectiveTargetPrice = signal.targetPrice;

		if (takeProfit > 0.0) {
			double takeProfitPrice = exitPriceForPnl(signal.side, signal.entryPrice, qty, takeProfit);
			if ("SHORT".equals(signal.side)) {
				plan.effectiveTargetPrice = roundToTwoDecimals(Math.max(signal.targetPrice, takeProfitPrice));
			} else {
				plan.effectiveTargetPrice = roundToTwoDecimals(Math.min(signal.targetPrice, takeProfitPrice));
			}
		}

		if (lossLimit > 0.0) {
			double lossPrice = exitPriceForPnl(signal.side, signal.entryPrice, qty, -lossLimit);
			if ("SHORT".equals(signal.side)) {
				plan.effectiveStopPrice = roundToTwoDecimals(Math.min(signal.stopPrice, lossPrice));
			} else {
				plan.effectiveStopPrice = roundToTwoDecimals(Math.max(signal.stopPrice, lossPrice));
			}
		}

		return plan;
	}

	private boolean openTrade(
		AlpacaManager alpacaManager,
		Session session,
		StrategyManager.LiveSignalSnapshot signal,
		PositionPlan plan,
		LocalDate tradingDay
	) {
		int quantity = (int) Math.max(1.0, Math.round(plan.qty));
		String response = alpacaManager.submitOrder(session.symbol, quantity, entryOrderSide(signal.side));
		if (orderFailed(response)) {
			synchronized (session) {
				session.lastDecision = "Alpaca rejected the " + signal.strategyName + " entry order.";
			}
			return false;
		}

		ActiveTradeState activeTrade = new ActiveTradeState();
		activeTrade.strategyCode = signal.strategyCode;
		activeTrade.strategyName = signal.strategyName;
		activeTrade.side = signal.side;
		activeTrade.qty = quantity;
		activeTrade.entryPrice = roundToTwoDecimals(signal.entryPrice);
		activeTrade.stopPrice = roundToTwoDecimals(signal.stopPrice);
		activeTrade.targetPrice = roundToTwoDecimals(signal.targetPrice);
		activeTrade.effectiveStopPrice = roundToTwoDecimals(plan.effectiveStopPrice);
		activeTrade.effectiveTargetPrice = roundToTwoDecimals(plan.effectiveTargetPrice);
		activeTrade.currentPrice = roundToTwoDecimals(signal.entryPrice);
		activeTrade.openedAt = signal.openedAt;
		activeTrade.tradeNotes = signal.tradeNotes;

		synchronized (session) {
			activeTrade.id = session.liveBotId > 0
				? DatabaseManager.insertLiveTrade(
					session.liveBotId,
					session.symbol,
					signal.side,
					quantity,
					signal.entryPrice,
					signal.openedAt,
					signal.strategyCode,
					signal.strategyName,
					signal.tradeNotes,
					"OPEN"
				)
				: -1;
			session.activeTrades.add(activeTrade);
			recalculateUnrealizedPnl(session);
			syncPrimaryActiveTrade(session);
			session.executedSignals.add(signalKey(signal));
			incrementTradesTaken(session, signal.strategyCode, tradingDay);
			session.lastDecision = signal.strategyName + " " + signal.side.toLowerCase() + " order sent at $" + roundToTwoDecimals(signal.entryPrice) + ".";
		}

		return true;
	}

	private boolean closeActiveTrade(AlpacaManager alpacaManager, Session session, CloseDecision decision) {
		ActiveTradeState trade;
		synchronized (session) {
			trade = session.activeTrade;
		}
		return closeTrade(alpacaManager, session, trade, decision);
	}

	private boolean closeTrade(
		AlpacaManager alpacaManager,
		Session session,
		ActiveTradeState trade,
		CloseDecision decision
	) {
		String symbol;

		synchronized (session) {
			if (trade == null || !session.activeTrades.contains(trade)) {
				return true;
			}
			symbol = session.symbol;
		}

		if (decision == null) {
			return true;
		}

		int quantity = (int) Math.max(1.0, Math.round(trade.qty));
		String response = alpacaManager.submitOrder(symbol, quantity, exitOrderSide(trade.side));
		if (orderFailed(response)) {
			synchronized (session) {
				session.lastDecision = "Alpaca rejected the exit order for the active trade.";
			}
			return false;
		}

		double realizedPnl = roundToTwoDecimals(calculatePnl(trade.side, trade.entryPrice, decision.exitPrice, trade.qty));
		String finalNotes = appendTradeNote(trade.tradeNotes, decision.reason);

		synchronized (session) {
			session.realizedPnl = roundToTwoDecimals(session.realizedPnl + realizedPnl);
			session.unrealizedPnl = 0.0;
			session.closedTradeCount++;
			if (realizedPnl >= 0.0) {
				session.grossProfit = roundToTwoDecimals(session.grossProfit + realizedPnl);
				session.winningTrades++;
			} else {
				session.grossLoss = roundToTwoDecimals(session.grossLoss + Math.abs(realizedPnl));
			}
			session.lastDecision = decision.reason + " Exit order sent at $" + roundToTwoDecimals(decision.exitPrice) + ".";

			TradeLog tradeLog = new TradeLog();
			tradeLog.id = trade.id;
			tradeLog.strategyCode = trade.strategyCode;
			tradeLog.strategyName = trade.strategyName;
			tradeLog.symbol = session.symbol;
			tradeLog.time = trade.openedAt;
			tradeLog.closedAt = decision.closedAt;
			tradeLog.side = trade.side;
			tradeLog.qty = roundToTwoDecimals(trade.qty);
			tradeLog.entry = roundToTwoDecimals(trade.entryPrice);
			tradeLog.exit = roundToTwoDecimals(decision.exitPrice);
			tradeLog.pnl = roundToTwoDecimals(realizedPnl);
			tradeLog.status = "CLOSED";
			tradeLog.tradeNotes = finalNotes;
			session.closedTrades.add(tradeLog);

			if (trade.id > 0) {
				DatabaseManager.closeLiveTrade(trade.id, decision.exitPrice, decision.closedAt, finalNotes, "CLOSED", realizedPnl);
			}

			session.activeTrades.remove(trade);
			recalculateUnrealizedPnl(session);
			syncPrimaryActiveTrade(session);
		}

		return true;
	}

	private CloseDecision resolveCloseDecision(ActiveTradeState trade, AlpacaManager.CachedBar latestBar) {
		if (trade == null || latestBar == null) {
			return null;
		}

		if ("SHORT".equals(trade.side)) {
			if (latestBar.high >= trade.effectiveStopPrice && latestBar.low <= trade.effectiveTargetPrice) {
				return new CloseDecision(trade.effectiveStopPrice, latestBar.displayTime, latestBar.marketDate, latestBar.marketTime, "Stop loss hit.");
			}
			if (latestBar.high >= trade.effectiveStopPrice) {
				return new CloseDecision(trade.effectiveStopPrice, latestBar.displayTime, latestBar.marketDate, latestBar.marketTime, "Stop loss hit.");
			}
			if (latestBar.low <= trade.effectiveTargetPrice) {
				return new CloseDecision(trade.effectiveTargetPrice, latestBar.displayTime, latestBar.marketDate, latestBar.marketTime, "Target reached.");
			}
		} else {
			if (latestBar.low <= trade.effectiveStopPrice && latestBar.high >= trade.effectiveTargetPrice) {
				return new CloseDecision(trade.effectiveStopPrice, latestBar.displayTime, latestBar.marketDate, latestBar.marketTime, "Stop loss hit.");
			}
			if (latestBar.low <= trade.effectiveStopPrice) {
				return new CloseDecision(trade.effectiveStopPrice, latestBar.displayTime, latestBar.marketDate, latestBar.marketTime, "Stop loss hit.");
			}
			if (latestBar.high >= trade.effectiveTargetPrice) {
				return new CloseDecision(trade.effectiveTargetPrice, latestBar.displayTime, latestBar.marketDate, latestBar.marketTime, "Target reached.");
			}
		}

		if (!latestBar.marketTime.isBefore(SESSION_EXIT_TIME)) {
			return new CloseDecision(latestBar.close, latestBar.displayTime, latestBar.marketDate, latestBar.marketTime, "Session close exit.");
		}

		return null;
	}

	private int activeTradeCount(Session session) {
		if (session == null || session.activeTrades == null) {
			return 0;
		}
		return session.activeTrades.size();
	}

	private String activeSide(Session session) {
		if (session == null || session.activeTrades == null || session.activeTrades.isEmpty()) {
			return "";
		}
		for (int index = 0; index < session.activeTrades.size(); index++) {
			ActiveTradeState trade = session.activeTrades.get(index);
			if (!isBlank(trade.side)) {
				return trade.side;
			}
		}
		return "";
	}

	private void syncPrimaryActiveTrade(Session session) {
		if (session == null || session.activeTrades == null || session.activeTrades.isEmpty()) {
			if (session != null) {
				session.activeTrade = null;
			}
			return;
		}
		session.activeTrade = session.activeTrades.get(0);
	}

	private void recalculateUnrealizedPnl(Session session) {
		if (session == null || session.activeTrades == null || session.activeTrades.isEmpty()) {
			if (session != null) {
				session.unrealizedPnl = 0.0;
			}
			return;
		}
		double total = 0.0;
		for (int index = 0; index < session.activeTrades.size(); index++) {
			total += calculateUnrealizedPnl(session.activeTrades.get(index));
		}
		session.unrealizedPnl = roundToTwoDecimals(total);
	}

	private double calculateUnrealizedPnl(ActiveTradeState trade) {
		if (trade == null) {
			return 0.0;
		}
		double markPrice = trade.currentPrice > 0.0 ? trade.currentPrice : trade.entryPrice;
		return calculatePnl(trade.side, trade.entryPrice, markPrice, trade.qty);
	}

	private double reservedNotional(List<ActiveTradeState> trades) {
		if (trades == null || trades.isEmpty()) {
			return 0.0;
		}
		double reserved = 0.0;
		for (int index = 0; index < trades.size(); index++) {
			ActiveTradeState trade = trades.get(index);
			reserved += Math.max(0.0, trade.entryPrice * trade.qty);
		}
		return roundToTwoDecimals(reserved);
	}

	private void refreshDerivedMetrics(Session session) {
		double grossPnl = roundToTwoDecimals(session.realizedPnl + session.unrealizedPnl);
		double modeledEquity = roundToTwoDecimals(session.startingEquity + grossPnl);
		if (modeledEquity > session.peakEquity) {
			session.peakEquity = modeledEquity;
		}
		if (session.peakEquity > 0.0) {
			double currentDrawdown = ((session.peakEquity - modeledEquity) / session.peakEquity) * 100.0;
			if (currentDrawdown > session.drawdown) {
				session.drawdown = roundToTwoDecimals(currentDrawdown);
			}
		}
		session.totalReturn = session.startingEquity <= 0.0
			? 0.0
			: roundToTwoDecimals((grossPnl / session.startingEquity) * 100.0);
		session.winRate = session.closedTradeCount == 0
			? 0.0
			: roundToTwoDecimals((session.winningTrades * 100.0) / session.closedTradeCount);
		session.profitFactor = session.grossLoss == 0.0
			? roundToTwoDecimals(session.grossProfit)
			: roundToTwoDecimals(session.grossProfit / session.grossLoss);
	}

	private void persistSession(Session session, String status) {
		if (session.liveBotId <= 0) {
			return;
		}

		double grossPnl = roundToTwoDecimals(session.realizedPnl + session.unrealizedPnl);
		double modeledEquity = roundToTwoDecimals(session.startingEquity + grossPnl);
		DatabaseManager.updateLiveBotRun(
			session.liveBotId,
			status,
			isBlank(session.lastPulledAt) ? formatDisplayTime(ZonedDateTime.now(MARKET_ZONE)) : session.lastPulledAt,
			modeledEquity,
			grossPnl,
			session.totalReturn,
			session.winRate,
			session.closedTradeCount + activeTradeCount(session)
		);
	}

	private LiveBotStatus buildStatus(Session session, boolean success, boolean requiresConfirmation, String message) {
		LiveBotStatus status = new LiveBotStatus();
		synchronized (session) {
			status.success = success;
			status.requiresConfirmation = requiresConfirmation;
			status.running = session.running;
			status.message = message;
			status.symbol = session.symbol;
			status.perTradeBuyingPower = roundToTwoDecimals(session.perTradeBuyingPower);
			status.takeProfit = roundToTwoDecimals(session.takeProfit);
			status.lossLimit = roundToTwoDecimals(session.lossLimit);
			status.cash = roundToTwoDecimals(session.cash);
			status.accountEquity = roundToTwoDecimals(session.accountEquity);
			status.startingEquity = roundToTwoDecimals(session.startingEquity);
			status.grossPnl = roundToTwoDecimals(session.realizedPnl + session.unrealizedPnl);
			status.realizedPnl = roundToTwoDecimals(session.realizedPnl);
			status.unrealizedPnl = roundToTwoDecimals(session.unrealizedPnl);
			status.totalReturn = roundToTwoDecimals(session.totalReturn);
			status.winRate = roundToTwoDecimals(session.winRate);
			status.drawdown = roundToTwoDecimals(session.drawdown);
			status.profitFactor = roundToTwoDecimals(session.profitFactor);
			syncPrimaryActiveTrade(session);
			status.trades = session.closedTradeCount + activeTradeCount(session);
			status.closedTrades = session.closedTradeCount;
			status.pullCount = session.pullCount;
			status.liveBotId = session.liveBotId;
			status.startedAt = session.startedAt;
			status.lastPulledAt = session.lastPulledAt;
			status.nextPullAt = session.nextPullAt;
			status.latestBarTime = session.latestBarTime;
			status.latestOpen = roundToTwoDecimals(session.latestOpen);
			status.latestHigh = roundToTwoDecimals(session.latestHigh);
			status.latestLow = roundToTwoDecimals(session.latestLow);
			status.latestPrice = roundToTwoDecimals(session.latestPrice);
			status.latestVolume = roundToTwoDecimals(session.latestVolume);
			status.latestVwap = roundToTwoDecimals(session.latestVwap);
			status.latestSma9 = roundToTwoDecimals(session.latestSma9);
			status.latestSma20 = roundToTwoDecimals(session.latestSma20);
			status.latestEma9 = roundToTwoDecimals(session.latestEma9);
			status.latestEma20 = roundToTwoDecimals(session.latestEma20);
			status.latestRsi14 = roundToTwoDecimals(session.latestRsi14);
			status.latestAtr14 = roundToTwoDecimals(session.latestAtr14);
			status.latestVolumeAverage20 = roundToTwoDecimals(session.latestVolumeAverage20);
			status.latestVolumeRatio = roundToTwoDecimals(session.latestVolumeRatio);
			status.orbHigh = roundToTwoDecimals(session.orbHigh);
			status.orbLow = roundToTwoDecimals(session.orbLow);
			status.lastDecision = session.lastDecision;
			status.enabledStrategies = new ArrayList<String>(session.enabledStrategies);
			status.tradeLogs = buildTradeLogs(session);
			status.activeTrades = copyActiveTrades(session.activeTrades);
			status.activeTrade = status.activeTrades.isEmpty() ? null : status.activeTrades.get(0);
			status.oneMinuteData = copyMarketDataPoints(session.oneMinuteData);
			status.fiveMinuteData = copyMarketDataPoints(session.fiveMinuteData);
			status.thirtyMinuteData = copyMarketDataPoints(session.thirtyMinuteData);
			status.oneHourData = copyMarketDataPoints(session.oneHourData);
			status.equityCurve = copyEquityPoints(session.equityCurve);
		}
		return status;
	}

	private LiveBotStatus buildIdleStatus(boolean success, boolean requiresConfirmation, String message) {
		LiveBotStatus status = new LiveBotStatus();
		status.success = success;
		status.requiresConfirmation = requiresConfirmation;
		status.running = false;
		status.message = message;
		status.symbol = "SPY";
		status.lastDecision = message;
		status.enabledStrategies = enabledStrategies(StrategyManager.loadStrategySettings());
		return status;
	}

	private ActiveTrade copyActiveTrade(ActiveTradeState trade, double unrealizedPnl) {
		if (trade == null) {
			return null;
		}

		ActiveTrade copy = new ActiveTrade();
		copy.id = trade.id;
		copy.strategyCode = trade.strategyCode;
		copy.strategyName = trade.strategyName;
		copy.side = trade.side;
		copy.qty = roundToTwoDecimals(trade.qty);
		copy.entryPrice = roundToTwoDecimals(trade.entryPrice);
		copy.stopPrice = roundToTwoDecimals(trade.stopPrice);
		copy.targetPrice = roundToTwoDecimals(trade.targetPrice);
		copy.effectiveStopPrice = roundToTwoDecimals(trade.effectiveStopPrice);
		copy.effectiveTargetPrice = roundToTwoDecimals(trade.effectiveTargetPrice);
		copy.currentPrice = roundToTwoDecimals(trade.currentPrice);
		copy.unrealizedPnl = roundToTwoDecimals(unrealizedPnl);
		copy.openedAt = trade.openedAt;
		copy.tradeNotes = trade.tradeNotes;
		return copy;
	}

	private List<ActiveTrade> copyActiveTrades(List<ActiveTradeState> trades) {
		List<ActiveTrade> copies = new ArrayList<ActiveTrade>();
		if (trades == null) {
			return copies;
		}
		for (int index = 0; index < trades.size(); index++) {
			ActiveTradeState trade = trades.get(index);
			ActiveTrade copy = copyActiveTrade(trade, calculateUnrealizedPnl(trade));
			if (copy != null) {
				copies.add(copy);
			}
		}
		return copies;
	}

	private List<TradeLog> buildTradeLogs(Session session) {
		List<TradeLog> logs = new ArrayList<TradeLog>();
		for (int index = 0; index < session.closedTrades.size(); index++) {
			TradeLog source = session.closedTrades.get(index);
			TradeLog copy = new TradeLog();
			copy.id = source.id;
			copy.strategyCode = source.strategyCode;
			copy.strategyName = source.strategyName;
			copy.symbol = source.symbol;
			copy.time = source.time;
			copy.closedAt = source.closedAt;
			copy.side = source.side;
			copy.qty = roundToTwoDecimals(source.qty);
			copy.entry = source.entry;
			copy.exit = source.exit;
			copy.pnl = source.pnl;
			copy.status = source.status;
			copy.tradeNotes = source.tradeNotes;
			logs.add(copy);
		}

		for (int index = 0; index < session.activeTrades.size(); index++) {
			ActiveTradeState activeTrade = session.activeTrades.get(index);
			TradeLog openTrade = new TradeLog();
			openTrade.id = activeTrade.id;
			openTrade.strategyCode = activeTrade.strategyCode;
			openTrade.strategyName = activeTrade.strategyName;
			openTrade.symbol = session.symbol;
			openTrade.time = activeTrade.openedAt;
			openTrade.closedAt = "";
			openTrade.side = activeTrade.side;
			openTrade.qty = roundToTwoDecimals(activeTrade.qty);
			openTrade.entry = roundToTwoDecimals(activeTrade.entryPrice);
			openTrade.exit = null;
			openTrade.pnl = roundToTwoDecimals(calculateUnrealizedPnl(activeTrade));
			openTrade.status = "OPEN";
			openTrade.tradeNotes = appendTradeNote(activeTrade.tradeNotes, "Position still open.");
			logs.add(openTrade);
		}

		Collections.sort(logs, new Comparator<TradeLog>() {
			@Override
			public int compare(TradeLog first, TradeLog second) {
				String firstTime = first.time == null ? "" : first.time;
				String secondTime = second.time == null ? "" : second.time;
				return firstTime.compareTo(secondTime);
			}
		});
		return logs;
	}

	private int tradesTakenForDay(Session session, String strategyCode, LocalDate tradingDay) {
		if ("IFVG".equals(strategyCode)) {
			Integer count = session.ifvgTradesTakenByDay.get(tradingDay);
			return count == null ? 0 : count.intValue();
		}
		if ("VWAP".equals(strategyCode)) {
			Integer count = session.vwapTradesTakenByDay.get(tradingDay);
			return count == null ? 0 : count.intValue();
		}
		if ("MRVWAP".equals(strategyCode)) {
			Integer count = session.vwapMeanReversionTradesTakenByDay.get(tradingDay);
			return count == null ? 0 : count.intValue();
		}
		if ("GAPGO".equals(strategyCode)) {
			Integer count = session.gapGoTradesTakenByDay.get(tradingDay);
			return count == null ? 0 : count.intValue();
		}
		Integer count = session.orbTradesTakenByDay.get(tradingDay);
		return count == null ? 0 : count.intValue();
	}

	private void incrementTradesTaken(Session session, String strategyCode, LocalDate tradingDay) {
		if ("IFVG".equals(strategyCode)) {
			session.ifvgTradesTakenByDay.put(tradingDay, tradesTakenForDay(session, strategyCode, tradingDay) + 1);
			return;
		}
		if ("VWAP".equals(strategyCode)) {
			session.vwapTradesTakenByDay.put(tradingDay, tradesTakenForDay(session, strategyCode, tradingDay) + 1);
			return;
		}
		if ("MRVWAP".equals(strategyCode)) {
			session.vwapMeanReversionTradesTakenByDay.put(tradingDay, tradesTakenForDay(session, strategyCode, tradingDay) + 1);
			return;
		}
		if ("GAPGO".equals(strategyCode)) {
			session.gapGoTradesTakenByDay.put(tradingDay, tradesTakenForDay(session, strategyCode, tradingDay) + 1);
			return;
		}
		session.orbTradesTakenByDay.put(tradingDay, tradesTakenForDay(session, strategyCode, tradingDay) + 1);
	}

	private String signalKey(StrategyManager.LiveSignalSnapshot signal) {
		return signal.strategyCode + "|" + signal.side + "|" + signal.openedAt;
	}

	private String signalEntryBucket(StrategyManager.LiveSignalSnapshot signal) {
		if (signal == null) {
			return "";
		}
		return safeString(signal.openedAt) + "|" + safeString(signal.side);
	}

	private boolean hasCrowdedActiveTrade(
		List<ActiveTradeState> activeTrades,
		StrategyManager.LiveSignalSnapshot signal
	) {
		if (activeTrades == null || activeTrades.isEmpty() || signal == null) {
			return false;
		}

		for (int index = 0; index < activeTrades.size(); index++) {
			ActiveTradeState trade = activeTrades.get(index);
			if (trade == null || trade.side == null || !trade.side.equals(signal.side)) {
				continue;
			}
			LocalTime tradeOpenedTime = parseTradeOpenedTime(trade.openedAt);
			if (
				tradeOpenedTime != null
				&& Math.abs(java.time.Duration.between(tradeOpenedTime, signal.openedTime).toMinutes())
					< StrategyManager.minimumMinutesBetweenPortfolioEntries()
			) {
				return true;
			}
			if (entryPriceDistancePct(trade.entryPrice, signal.entryPrice) < StrategyManager.minimumDistinctEntryPricePct()) {
				return true;
			}
		}

		return false;
	}

	private boolean hasCrowdedSelectedSignal(
		List<StrategyManager.LiveSignalSnapshot> selected,
		StrategyManager.LiveSignalSnapshot candidate
	) {
		if (selected == null || selected.isEmpty() || candidate == null) {
			return false;
		}

		for (int index = 0; index < selected.size(); index++) {
			StrategyManager.LiveSignalSnapshot signal = selected.get(index);
			if (signal == null || signal.side == null || !signal.side.equals(candidate.side)) {
				continue;
			}
			if (signalEntryBucket(signal).equals(signalEntryBucket(candidate))) {
				return true;
			}
			if (
				signal.openedTime != null
				&& candidate.openedTime != null
				&& Math.abs(java.time.Duration.between(signal.openedTime, candidate.openedTime).toMinutes())
					< StrategyManager.minimumMinutesBetweenPortfolioEntries()
			) {
				return true;
			}
			if (entryPriceDistancePct(signal.entryPrice, candidate.entryPrice) < StrategyManager.minimumDistinctEntryPricePct()) {
				return true;
			}
		}

		return false;
	}

	private LocalTime parseTradeOpenedTime(String openedAt) {
		if (isBlank(openedAt)) {
			return null;
		}
		try {
			return LocalDateTime.parse(openedAt, DISPLAY_TIME_FORMAT).toLocalTime();
		} catch (Exception e) {
			return null;
		}
	}

	private double entryPriceDistancePct(double firstPrice, double secondPrice) {
		double referencePrice = Math.max(1.0, Math.abs(secondPrice));
		return Math.abs(firstPrice - secondPrice) / referencePrice * 100.0;
	}

	private StrategyManager.StrategyConfig configForCode(StrategyManager.StrategySettings settings, String strategyCode) {
		if (settings == null) {
			return null;
		}
		if ("IFVG".equals(strategyCode)) {
			return settings.ifvg;
		}
		if ("VWAP".equals(strategyCode)) {
			return settings.vwapPullback;
		}
		if ("MRVWAP".equals(strategyCode)) {
			return settings.vwapMeanReversion;
		}
		if ("GAPGO".equals(strategyCode)) {
			return settings.gapGo;
		}
		return settings.orb;
	}

	private List<String> enabledStrategies(StrategyManager.StrategySettings settings) {
		List<String> enabled = new ArrayList<String>();
		if (settings == null) {
			return enabled;
		}
		if (settings.orb != null && settings.orb.isEnabled) {
			enabled.add(settings.orb.strategyName);
		}
		if (settings.ifvg != null && settings.ifvg.isEnabled) {
			enabled.add(settings.ifvg.strategyName);
		}
		if (settings.vwapPullback != null && settings.vwapPullback.isEnabled) {
			enabled.add(settings.vwapPullback.strategyName);
		}
		if (settings.vwapMeanReversion != null && settings.vwapMeanReversion.isEnabled) {
			enabled.add(settings.vwapMeanReversion.strategyName);
		}
		if (settings.gapGo != null && settings.gapGo.isEnabled) {
			enabled.add(settings.gapGo.strategyName);
		}
		return enabled;
	}

	private AlpacaManager createAlpacaManager(String email) {
		if (accountManager == null || isBlank(email)) {
			return null;
		}

		String apiKey = accountManager.getBrokerApiKey(email);
		String secretKey = accountManager.getBrokerSecretKey(email);
		if (isBlank(apiKey) || isBlank(secretKey)) {
			return null;
		}
		return new AlpacaManager(apiKey, secretKey);
	}

	private List<MarketDataPoint> buildMarketDataPoints(List<AlpacaManager.CachedBar> bars, int maxPoints) {
		List<MarketDataPoint> points = new ArrayList<MarketDataPoint>();
		if (bars == null || bars.isEmpty()) {
			return points;
		}

		int firstIncludedIndex = Math.max(0, bars.size() - Math.max(1, maxPoints));
		double cumulativeTypicalVolume = 0.0;
		double cumulativeVolume = 0.0;
		double ema9 = 0.0;
		double ema20 = 0.0;

		for (int index = 0; index < bars.size(); index++) {
			AlpacaManager.CachedBar bar = bars.get(index);
			double typicalPrice = bar.vwap > 0.0 ? bar.vwap : (bar.high + bar.low + bar.close) / 3.0;
			cumulativeTypicalVolume += typicalPrice * bar.volume;
			cumulativeVolume += bar.volume;

			if (index == 0) {
				ema9 = bar.close;
				ema20 = bar.close;
			} else {
				ema9 = calculateNextEma(bar.close, ema9, 9);
				ema20 = calculateNextEma(bar.close, ema20, 20);
			}

			if (index < firstIncludedIndex) {
				continue;
			}

			double volumeAverage20 = averageVolume(bars, Math.max(0, index - 19), index);
			MarketDataPoint point = new MarketDataPoint();
			point.time = bar.displayTime;
			point.open = roundToFourDecimals(bar.open);
			point.high = roundToFourDecimals(bar.high);
			point.low = roundToFourDecimals(bar.low);
			point.close = roundToFourDecimals(bar.close);
			point.volume = roundToTwoDecimals(bar.volume);
			point.tradeCount = bar.tradeCount;
			point.vwap = cumulativeVolume <= 0.0 ? 0.0 : roundToFourDecimals(cumulativeTypicalVolume / cumulativeVolume);
			point.sma9 = roundToFourDecimals(averageClose(bars, Math.max(0, index - 8), index));
			point.sma20 = roundToFourDecimals(averageClose(bars, Math.max(0, index - 19), index));
			point.ema9 = roundToFourDecimals(ema9);
			point.ema20 = roundToFourDecimals(ema20);
			point.rsi14 = roundToTwoDecimals(calculateRsi(bars, index, 14));
			point.atr14 = roundToFourDecimals(calculateAtr(bars, index, 14));
			point.volumeAverage20 = roundToTwoDecimals(volumeAverage20);
			point.volumeRatio = volumeAverage20 <= 0.0 ? 0.0 : roundToTwoDecimals(bar.volume / volumeAverage20);
			points.add(point);
		}

		return points;
	}

	private double calculateNextEma(double close, double previousEma, int period) {
		double multiplier = 2.0 / (period + 1.0);
		return (close - previousEma) * multiplier + previousEma;
	}

	private double averageClose(List<AlpacaManager.CachedBar> bars, int startIndex, int endIndex) {
		if (bars == null || bars.isEmpty() || endIndex < startIndex) {
			return 0.0;
		}
		double total = 0.0;
		int count = 0;
		for (int index = startIndex; index <= endIndex && index < bars.size(); index++) {
			total += bars.get(index).close;
			count++;
		}
		return count == 0 ? 0.0 : total / count;
	}

	private double averageVolume(List<AlpacaManager.CachedBar> bars, int startIndex, int endIndex) {
		if (bars == null || bars.isEmpty() || endIndex < startIndex) {
			return 0.0;
		}
		double total = 0.0;
		int count = 0;
		for (int index = startIndex; index <= endIndex && index < bars.size(); index++) {
			total += bars.get(index).volume;
			count++;
		}
		return count == 0 ? 0.0 : total / count;
	}

	private double calculateRsi(List<AlpacaManager.CachedBar> bars, int index, int period) {
		if (bars == null || index < period || period <= 0) {
			return 0.0;
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
			return averageGain == 0.0 ? 50.0 : 100.0;
		}
		double relativeStrength = averageGain / averageLoss;
		return 100.0 - (100.0 / (1.0 + relativeStrength));
	}

	private double calculateAtr(List<AlpacaManager.CachedBar> bars, int index, int period) {
		if (bars == null || bars.isEmpty() || index < 0 || period <= 0) {
			return 0.0;
		}
		int startIndex = Math.max(0, index - period + 1);
		double total = 0.0;
		int count = 0;
		for (int cursor = startIndex; cursor <= index && cursor < bars.size(); cursor++) {
			AlpacaManager.CachedBar bar = bars.get(cursor);
			double previousClose = cursor == 0 ? bar.close : bars.get(cursor - 1).close;
			double trueRange = Math.max(
				bar.high - bar.low,
				Math.max(Math.abs(bar.high - previousClose), Math.abs(bar.low - previousClose))
			);
			total += trueRange;
			count++;
		}
		return count == 0 ? 0.0 : total / count;
	}

	private double calculateOpeningRangeHigh(List<AlpacaManager.CachedBar> bars, int orbWindowMinutes) {
		if (bars == null || bars.isEmpty()) {
			return 0.0;
		}
		int count = Math.min(Math.max(1, orbWindowMinutes), bars.size());
		double high = bars.get(0).high;
		for (int index = 1; index < count; index++) {
			high = Math.max(high, bars.get(index).high);
		}
		return roundToFourDecimals(high);
	}

	private double calculateOpeningRangeLow(List<AlpacaManager.CachedBar> bars, int orbWindowMinutes) {
		if (bars == null || bars.isEmpty()) {
			return 0.0;
		}
		int count = Math.min(Math.max(1, orbWindowMinutes), bars.size());
		double low = bars.get(0).low;
		for (int index = 1; index < count; index++) {
			low = Math.min(low, bars.get(index).low);
		}
		return roundToFourDecimals(low);
	}

	private void appendEquityPoint(Session session, ZonedDateTime timestamp) {
		double totalPnl = roundToTwoDecimals(session.realizedPnl + session.unrealizedPnl);
		double modeledEquity = roundToTwoDecimals(session.startingEquity + totalPnl);
		double displayEquity = session.accountEquity > 0.0 ? session.accountEquity : modeledEquity;
		EquityPoint point = new EquityPoint();
		point.time = formatDisplayTime(timestamp);
		point.equity = roundToTwoDecimals(displayEquity);
		point.cash = roundToTwoDecimals(session.cash);
		point.realizedPnl = roundToTwoDecimals(session.realizedPnl);
		point.unrealizedPnl = roundToTwoDecimals(session.unrealizedPnl);
		point.totalPnl = totalPnl;

		if (!session.equityCurve.isEmpty()) {
			EquityPoint lastPoint = session.equityCurve.get(session.equityCurve.size() - 1);
			if (point.time.equals(lastPoint.time)) {
				session.equityCurve.set(session.equityCurve.size() - 1, point);
				return;
			}
		}

		session.equityCurve.add(point);
		while (session.equityCurve.size() > 240) {
			session.equityCurve.remove(0);
		}
	}

	private List<MarketDataPoint> copyMarketDataPoints(List<MarketDataPoint> source) {
		List<MarketDataPoint> copies = new ArrayList<MarketDataPoint>();
		if (source == null) {
			return copies;
		}
		for (int index = 0; index < source.size(); index++) {
			MarketDataPoint point = source.get(index);
			MarketDataPoint copy = new MarketDataPoint();
			copy.time = point.time;
			copy.open = point.open;
			copy.high = point.high;
			copy.low = point.low;
			copy.close = point.close;
			copy.volume = point.volume;
			copy.tradeCount = point.tradeCount;
			copy.vwap = point.vwap;
			copy.sma9 = point.sma9;
			copy.sma20 = point.sma20;
			copy.ema9 = point.ema9;
			copy.ema20 = point.ema20;
			copy.rsi14 = point.rsi14;
			copy.atr14 = point.atr14;
			copy.volumeAverage20 = point.volumeAverage20;
			copy.volumeRatio = point.volumeRatio;
			copies.add(copy);
		}
		return copies;
	}

	private List<EquityPoint> copyEquityPoints(List<EquityPoint> source) {
		List<EquityPoint> copies = new ArrayList<EquityPoint>();
		if (source == null) {
			return copies;
		}
		for (int index = 0; index < source.size(); index++) {
			EquityPoint point = source.get(index);
			EquityPoint copy = new EquityPoint();
			copy.time = point.time;
			copy.equity = point.equity;
			copy.cash = point.cash;
			copy.realizedPnl = point.realizedPnl;
			copy.unrealizedPnl = point.unrealizedPnl;
			copy.totalPnl = point.totalPnl;
			copies.add(copy);
		}
		return copies;
	}

	private String entryOrderSide(String strategySide) {
		return "SHORT".equals(strategySide) ? "sell" : "buy";
	}

	private String exitOrderSide(String strategySide) {
		return "SHORT".equals(strategySide) ? "buy" : "sell";
	}

	private boolean orderFailed(String response) {
		return response == null || response.toLowerCase().contains("\"error\"");
	}

	private double deriveStartingEquity(AlpacaManager.AccountSnapshot snapshot) {
		if (snapshot == null) {
			return 0.0;
		}
		if (snapshot.equity > 0.0) {
			return snapshot.equity;
		}
		if (snapshot.portfolioValue > 0.0) {
			return snapshot.portfolioValue;
		}
		if (snapshot.cash > 0.0) {
			return snapshot.cash;
		}
		return snapshot.buyingPower;
	}

	private static String formatDisplayTime(ZonedDateTime value) {
		return value.withZoneSameInstant(MARKET_ZONE).format(DISPLAY_TIME_FORMAT);
	}

	private static double calculatePnl(String side, double entryPrice, double exitPrice, double qty) {
		if ("SHORT".equals(side)) {
			return roundToTwoDecimals((entryPrice - exitPrice) * qty);
		}
		return roundToTwoDecimals((exitPrice - entryPrice) * qty);
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

	private static String appendTradeNote(String original, String addition) {
		if (isBlank(addition)) {
			return original == null ? "" : original;
		}
		String safeAddition = addition.endsWith(".") ? addition : addition + ".";
		if (isBlank(original)) {
			return safeAddition;
		}
		return original + " " + safeAddition;
	}

	private static double positiveOrDefault(double value, double defaultValue) {
		return value > 0.0 ? roundToTwoDecimals(value) : defaultValue;
	}

	private static double valueOrDefault(double value, double defaultValue) {
		return value > 0.0 ? roundToTwoDecimals(value) : roundToTwoDecimals(defaultValue);
	}

	private static double roundToTwoDecimals(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static double roundToFourDecimals(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static String safeString(String value) {
		return value == null ? "" : value;
	}

	private static String tradeLogsToJson(List<TradeLog> tradeLogs) {
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < tradeLogs.size(); index++) {
			TradeLog trade = tradeLogs.get(index);
			if (index > 0) {
				json.append(",");
			}
			json.append("{")
				.append("\"id\":").append(trade.id).append(",")
				.append("\"strategyCode\":").append(jsonString(trade.strategyCode)).append(",")
				.append("\"strategyName\":").append(jsonString(trade.strategyName)).append(",")
				.append("\"symbol\":").append(jsonString(trade.symbol)).append(",")
				.append("\"time\":").append(jsonString(trade.time)).append(",")
				.append("\"closedAt\":").append(jsonString(trade.closedAt)).append(",")
				.append("\"side\":").append(jsonString(trade.side)).append(",")
				.append("\"qty\":").append(roundToTwoDecimals(trade.qty)).append(",")
				.append("\"entry\":").append(nullableDouble(trade.entry)).append(",")
				.append("\"exit\":").append(nullableDouble(trade.exit)).append(",")
				.append("\"pnl\":").append(nullableDouble(trade.pnl)).append(",")
				.append("\"status\":").append(jsonString(trade.status)).append(",")
				.append("\"tradeNotes\":").append(jsonString(trade.tradeNotes))
				.append("}");
		}
		json.append("]");
		return json.toString();
	}

	private static String marketDataToJson(LiveBotStatus status) {
		return "{"
			+ "\"1Min\":" + marketDataPointsToJson(status.oneMinuteData) + ","
			+ "\"5Min\":" + marketDataPointsToJson(status.fiveMinuteData) + ","
			+ "\"30Min\":" + marketDataPointsToJson(status.thirtyMinuteData) + ","
			+ "\"1Hour\":" + marketDataPointsToJson(status.oneHourData)
			+ "}";
	}

	private static String marketDataPointsToJson(List<MarketDataPoint> points) {
		List<MarketDataPoint> safePoints = points == null ? Collections.<MarketDataPoint>emptyList() : points;
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < safePoints.size(); index++) {
			MarketDataPoint point = safePoints.get(index);
			if (index > 0) {
				json.append(",");
			}
			json.append("{")
				.append("\"time\":").append(jsonString(point.time)).append(",")
				.append("\"open\":").append(roundToFourDecimals(point.open)).append(",")
				.append("\"high\":").append(roundToFourDecimals(point.high)).append(",")
				.append("\"low\":").append(roundToFourDecimals(point.low)).append(",")
				.append("\"close\":").append(roundToFourDecimals(point.close)).append(",")
				.append("\"volume\":").append(roundToTwoDecimals(point.volume)).append(",")
				.append("\"tradeCount\":").append(point.tradeCount).append(",")
				.append("\"vwap\":").append(roundToFourDecimals(point.vwap)).append(",")
				.append("\"sma9\":").append(roundToFourDecimals(point.sma9)).append(",")
				.append("\"sma20\":").append(roundToFourDecimals(point.sma20)).append(",")
				.append("\"ema9\":").append(roundToFourDecimals(point.ema9)).append(",")
				.append("\"ema20\":").append(roundToFourDecimals(point.ema20)).append(",")
				.append("\"rsi14\":").append(roundToTwoDecimals(point.rsi14)).append(",")
				.append("\"atr14\":").append(roundToFourDecimals(point.atr14)).append(",")
				.append("\"volumeAverage20\":").append(roundToTwoDecimals(point.volumeAverage20)).append(",")
				.append("\"volumeRatio\":").append(roundToTwoDecimals(point.volumeRatio))
				.append("}");
		}
		json.append("]");
		return json.toString();
	}

	private static String equityPointsToJson(List<EquityPoint> points) {
		List<EquityPoint> safePoints = points == null ? Collections.<EquityPoint>emptyList() : points;
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < safePoints.size(); index++) {
			EquityPoint point = safePoints.get(index);
			if (index > 0) {
				json.append(",");
			}
			json.append("{")
				.append("\"time\":").append(jsonString(point.time)).append(",")
				.append("\"equity\":").append(roundToTwoDecimals(point.equity)).append(",")
				.append("\"cash\":").append(roundToTwoDecimals(point.cash)).append(",")
				.append("\"realizedPnl\":").append(roundToTwoDecimals(point.realizedPnl)).append(",")
				.append("\"unrealizedPnl\":").append(roundToTwoDecimals(point.unrealizedPnl)).append(",")
				.append("\"totalPnl\":").append(roundToTwoDecimals(point.totalPnl))
				.append("}");
		}
		json.append("]");
		return json.toString();
	}

	private static String activeTradeToJson(ActiveTrade trade) {
		if (trade == null) {
			return "null";
		}

		return "{"
			+ "\"id\":" + trade.id + ","
			+ "\"strategyCode\":" + jsonString(trade.strategyCode) + ","
			+ "\"strategyName\":" + jsonString(trade.strategyName) + ","
			+ "\"side\":" + jsonString(trade.side) + ","
			+ "\"qty\":" + roundToTwoDecimals(trade.qty) + ","
			+ "\"entryPrice\":" + roundToTwoDecimals(trade.entryPrice) + ","
			+ "\"stopPrice\":" + roundToTwoDecimals(trade.stopPrice) + ","
			+ "\"targetPrice\":" + roundToTwoDecimals(trade.targetPrice) + ","
			+ "\"effectiveStopPrice\":" + roundToTwoDecimals(trade.effectiveStopPrice) + ","
			+ "\"effectiveTargetPrice\":" + roundToTwoDecimals(trade.effectiveTargetPrice) + ","
			+ "\"currentPrice\":" + roundToTwoDecimals(trade.currentPrice) + ","
			+ "\"unrealizedPnl\":" + roundToTwoDecimals(trade.unrealizedPnl) + ","
			+ "\"openedAt\":" + jsonString(trade.openedAt) + ","
			+ "\"tradeNotes\":" + jsonString(trade.tradeNotes)
			+ "}";
	}

	private static String activeTradesToJson(List<ActiveTrade> trades) {
		List<ActiveTrade> safeTrades = trades == null ? Collections.<ActiveTrade>emptyList() : trades;
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < safeTrades.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			json.append(activeTradeToJson(safeTrades.get(index)));
		}
		json.append("]");
		return json.toString();
	}

	private static String stringListToJson(List<String> values) {
		List<String> safeValues = values == null ? Collections.<String>emptyList() : values;
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < safeValues.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			json.append(jsonString(safeValues.get(index)));
		}
		json.append("]");
		return json.toString();
	}

	private static String nullableDouble(Double value) {
		return value == null ? "null" : Double.toString(roundToTwoDecimals(value.doubleValue()));
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
}
