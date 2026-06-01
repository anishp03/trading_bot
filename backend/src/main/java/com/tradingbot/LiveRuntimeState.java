package com.tradingbot;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public class LiveRuntimeState {
	private static final DateTimeFormatter DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter SERVER_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final java.time.ZoneId NEW_YORK_ZONE = java.time.ZoneId.of("America/New_York");
	private static final String DEFAULT_SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
	private static final String[] CACHE_TIMEFRAMES = new String[] { "1m", "5m", "15m", "30m", "1h" };
	private static final int MAX_RECENT_TRADES = 100;
	private static final long MARKET_DATA_FRESH_SECONDS = 30L;
	private static final long ORDER_FLOW_WINDOW_MS = 120000L;
	private static final long ORDER_FLOW_FRESH_SECONDS = 10L;
	private static final int ORDER_FLOW_LEVELS = 10;

	private static final ConcurrentHashMap<String, Double> latestPriceBySymbol = new ConcurrentHashMap<String, Double>();
	private static final ConcurrentHashMap<String, LiveCandle> currentCandleBySymbolTimeframe = new ConcurrentHashMap<String, LiveCandle>();
	private static final ConcurrentHashMap<String, String> brokerPositionsByKey = new ConcurrentHashMap<String, String>();
	private static final ConcurrentHashMap<String, String> brokerOrdersByKey = new ConcurrentHashMap<String, String>();
	private static final ConcurrentHashMap<String, OrderFlowBook> orderFlowBySymbol = new ConcurrentHashMap<String, OrderFlowBook>();
	private static final Deque<String> brokerTrades = new ArrayDeque<String>();

	private static volatile String latestMarketEventAt = "";
	private static volatile String latestBrokerEventAt = "";
	private static volatile String lastBrokerSyncAt = "";
	private static volatile String brokerAccountId = "";
	private static volatile String brokerPositionsJson = "[]";
	private static volatile String brokerOrdersJson = "[]";
	private static volatile String brokerTradesJson = "[]";
	private static volatile double accountSize = Double.NaN;
	private static volatile double currentBalance = Double.NaN;
	private static volatile double currentPnl = Double.NaN;
	private static volatile double realizedPnl = Double.NaN;
	private static volatile double unrealizedPnl = Double.NaN;
	private static volatile double drawdown = Double.NaN;
	private static volatile double returnPct = Double.NaN;
	private static volatile int numberOfTrades = -1;
	private static volatile int openTrades = -1;
	private static final ConcurrentHashMap<String, Long> lastMetricCheckLogAtByKey = new ConcurrentHashMap<String, Long>();

	public static String currentLatestMarketEventAt() {
		return cleanOrDefault(latestMarketEventAt, "");
	}

	public static void recordRealtimeEvent(
		String hub,
		String eventType,
		String accountId,
		String contractId,
		String symbol,
		String payloadJson,
		String receivedAt
	) {
		try {
			if ("market".equalsIgnoreCase(cleanOrDefault(hub, ""))) {
				recordMarketEvent(eventType, contractId, symbol, payloadJson, receivedAt);
			} else if ("user".equalsIgnoreCase(cleanOrDefault(hub, ""))) {
				recordUserEvent(eventType, accountId, contractId, symbol, payloadJson, receivedAt);
			}
		} catch (Exception e) {
			System.err.println("Live runtime cache update failed: " + safeMessage(e.getMessage()));
		}
	}

	public static void updateBrokerMetricsJson(String brokerMetricsJson) {
		try {
			if (brokerMetricsJson == null || !brokerMetricsJson.contains("\"success\":true")) {
				return;
			}
			double cachedAccountSize = jsonFirstNumber(brokerMetricsJson, new String[] { "accountSize" }, Double.NaN);
			double cachedCurrentBalance = jsonFirstNumber(brokerMetricsJson, new String[] { "currentBalance", "balance", "cashBalance" }, Double.NaN);
			double cachedCurrentPnl = jsonFirstNumber(brokerMetricsJson, new String[] { "currentPnl" }, Double.NaN);
			double cachedRealizedPnl = jsonFirstNumber(brokerMetricsJson, new String[] { "realizedPnl", "closedTradePnl" }, Double.NaN);
			double cachedUnrealizedPnl = jsonFirstNumber(brokerMetricsJson, new String[] { "unrealizedPnl" }, Double.NaN);
			double cachedDrawdown = jsonFirstNumber(brokerMetricsJson, new String[] { "drawdown" }, Double.NaN);
			double cachedReturnPct = jsonFirstNumber(brokerMetricsJson, new String[] { "returnPct" }, Double.NaN);
			double cachedTrades = jsonFirstNumber(brokerMetricsJson, new String[] { "numberOfTrades" }, Double.NaN);
			double cachedOpenTrades = jsonFirstNumber(brokerMetricsJson, new String[] { "openTrades" }, Double.NaN);
			String cachedAccountId = jsonText(brokerMetricsJson, "accountId", "");
			if (cachedAccountId.length() > 0) brokerAccountId = cachedAccountId;

			if (!Double.isNaN(cachedAccountSize)) accountSize = cachedAccountSize;
			if (!Double.isNaN(cachedCurrentBalance)) currentBalance = cachedCurrentBalance;
			if (!Double.isNaN(cachedCurrentPnl)) currentPnl = cachedCurrentPnl;
			if (!Double.isNaN(cachedRealizedPnl)) realizedPnl = cachedRealizedPnl;
			if (!Double.isNaN(cachedUnrealizedPnl)) unrealizedPnl = cachedUnrealizedPnl;
			if (!Double.isNaN(cachedDrawdown)) drawdown = Math.abs(cachedDrawdown);
			if (!Double.isNaN(cachedReturnPct)) returnPct = cachedReturnPct;
			if (!Double.isNaN(cachedTrades)) numberOfTrades = (int) Math.round(cachedTrades);
			if (!Double.isNaN(cachedOpenTrades)) openTrades = (int) Math.round(cachedOpenTrades);

			String positions = jsonArrayOrDefault(brokerMetricsJson, "positions", "[]");
			String orders = jsonArrayOrDefault(brokerMetricsJson, "orders", "[]");
			String trades = jsonArrayOrDefault(brokerMetricsJson, "trades", "[]");
			brokerPositionsJson = positions;
			brokerOrdersJson = orders;
			brokerTradesJson = trades;
			if (positions.length() <= 2) {
				brokerPositionsByKey.clear();
				if (Double.isNaN(cachedOpenTrades)) openTrades = 0;
			}
			if (orders.length() <= 2) {
				brokerOrdersByKey.clear();
			}
			synchronized (brokerTrades) {
				brokerTrades.clear();
			}
			String syncedAt = jsonText(brokerMetricsJson, "syncedAt", "");
			lastBrokerSyncAt = cleanOrDefault(syncedAt, serverTime());
		} catch (Exception e) {
			System.err.println("Broker metrics cache update failed: " + safeMessage(e.getMessage()));
		}
	}

	public static String getLiveMarksJson(String symbols, String timeframe) {
		return getLiveMarksJson(symbols, timeframe, "");
	}

	public static String getLiveMarksJson(String symbols, String timeframe, String accountId) {
		List<String> symbolList = parseSymbols(cleanOrDefault(symbols, DEFAULT_SYMBOLS));
		String normalizedTimeframe = normalizeTimeframe(timeframe);
		String expectedAccountId = cleanOrDefault(accountId, "");
		boolean accountMatches = expectedAccountId.length() == 0 || brokerAccountId.length() == 0 || expectedAccountId.equals(brokerAccountId);
		String serverTime = serverTime();
		String lastEventAt = latestMarketEventAt;
		long feedStaleSeconds = secondsSinceDisplayTime(lastEventAt);
		boolean realtimeRunning = ProjectXRealtimeManager.isRunning();
		boolean feedFresh = realtimeRunning && feedStaleSeconds >= 0L && feedStaleSeconds <= MARKET_DATA_FRESH_SECONDS;

		StringBuilder symbolsJson = new StringBuilder("{");
		int symbolMarkCount = 0;
		int candleMarkCount = 0;
		for (int index = 0; index < symbolList.size(); index++) {
			String symbol = symbolList.get(index);
			if (index > 0) {
				symbolsJson.append(",");
			}
			LiveCandle candle = currentCandleBySymbolTimeframe.get(cacheKey(symbol, normalizedTimeframe));
			if (candle == null) {
				candle = currentCandleBySymbolTimeframe.get(cacheKey(symbol, "1m"));
			}
			Double latestPrice = latestPriceBySymbol.get(symbol);
			if (latestPrice != null && latestPrice.doubleValue() > 0.0) {
				symbolMarkCount++;
			}
			if (candle != null) {
				candleMarkCount++;
			}
			symbolsJson.append(jsonString(symbol)).append(":{")
				.append("\"lastPrice\":").append(numberOrNull(latestPrice == null ? Double.NaN : latestPrice.doubleValue())).append(",")
				.append("\"currentCandle\":").append(candle == null ? "null" : candle.toJson())
				.append("}");
		}
		symbolsJson.append("}");

		String diagnosticPositionsJson = accountMatches ? filterJsonArrayByAccount(positionsJson(), expectedAccountId) : "[]";
		String diagnosticOrdersJson = accountMatches ? filterJsonArrayByAccount(ordersJson(), expectedAccountId) : "[]";
		String diagnosticTradesJson = accountMatches ? filterJsonArrayByAccount(tradesJson(), expectedAccountId) : "[]";
		MetricChecks checks = buildMetricChecks(
			realtimeRunning,
			feedFresh,
			feedStaleSeconds,
			symbolList.size(),
			symbolMarkCount,
			candleMarkCount,
			accountMatches,
			expectedAccountId,
			brokerAccountId,
			accountMatches ? currentBalance : Double.NaN,
			accountMatches ? currentPnl : Double.NaN,
			diagnosticPositionsJson,
			diagnosticOrdersJson,
			diagnosticTradesJson
		);
		logMetricChecks(checks);

		return "{"
			+ "\"success\":true,"
			+ "\"serverTime\":" + jsonString(serverTime) + ","
			+ "\"lastEventAt\":" + jsonString(lastEventAt) + ","
			+ "\"lastBrokerEventAt\":" + jsonString(latestBrokerEventAt) + ","
			+ "\"lastBrokerSyncAt\":" + jsonString(lastBrokerSyncAt) + ","
			+ "\"accountId\":" + jsonString(accountMatches ? firstNonBlank(brokerAccountId, expectedAccountId) : expectedAccountId) + ","
			+ "\"brokerAccountMatched\":" + accountMatches + ","
			+ "\"brokerDataAuthoritative\":false,"
			+ "\"dataSource\":\"LIVE_MARKS\","
			+ "\"transport\":\"live-marks-v2\","
			+ "\"feedFresh\":" + feedFresh + ","
			+ "\"feedStaleSeconds\":" + feedStaleSeconds + ","
			+ "\"timeframe\":" + jsonString(normalizedTimeframe) + ","
			+ "\"checks\":" + checks.toJson() + ","
			+ "\"symbols\":" + symbolsJson + ","
			+ "\"account\":{"
				+ "\"source\":\"TOPSTEPX_METRICS_ENDPOINT\","
				+ "\"accountId\":" + jsonString(accountMatches ? firstNonBlank(brokerAccountId, expectedAccountId) : expectedAccountId) + ","
				+ "\"accountSize\":null,"
				+ "\"currentBalance\":null,"
				+ "\"currentPnl\":null,"
				+ "\"realizedPnl\":null,"
				+ "\"unrealizedPnl\":null,"
				+ "\"drawdown\":null,"
				+ "\"returnPct\":null,"
				+ "\"numberOfTrades\":0,"
				+ "\"openTrades\":0"
			+ "},"
			+ "\"positions\":[],"
			+ "\"orders\":[],"
			+ "\"trades\":[],"
			+ "\"diagnostics\":{"
				+ "\"cachedBrokerAccountId\":" + jsonString(brokerAccountId) + ","
				+ "\"brokerPositionCount\":" + countJsonArrayObjects(diagnosticPositionsJson) + ","
				+ "\"brokerOrderCount\":" + countJsonArrayObjects(diagnosticOrdersJson) + ","
				+ "\"brokerTradeCount\":" + countJsonArrayObjects(diagnosticTradesJson)
			+ "}"
			+ "}";
	}

	static void clearForTest() {
		latestPriceBySymbol.clear();
		currentCandleBySymbolTimeframe.clear();
		brokerPositionsByKey.clear();
		brokerOrdersByKey.clear();
		synchronized (brokerTrades) {
			brokerTrades.clear();
		}
		latestMarketEventAt = "";
		latestBrokerEventAt = "";
		lastBrokerSyncAt = "";
		brokerAccountId = "";
		brokerPositionsJson = "[]";
		brokerOrdersJson = "[]";
		brokerTradesJson = "[]";
		accountSize = Double.NaN;
		currentBalance = Double.NaN;
		currentPnl = Double.NaN;
		realizedPnl = Double.NaN;
		unrealizedPnl = Double.NaN;
		drawdown = Double.NaN;
		returnPct = Double.NaN;
		numberOfTrades = -1;
		openTrades = -1;
		orderFlowBySymbol.clear();
		lastMetricCheckLogAtByKey.clear();
	}

	private static void recordMarketEvent(String eventType, String contractId, String symbol, String payloadJson, String receivedAt) {
		String resolvedSymbol = normalizeRealtimeSymbol(firstNonBlank(symbol, jsonText(payloadJson, "symbolName", ""), jsonText(payloadJson, "symbol", ""), contractId));
		if (resolvedSymbol.length() == 0) {
			return;
		}
		String normalizedType = cleanOrDefault(eventType, "");
		String eventTime = cleanOrDefault(receivedAt, displayTime());
		if ("GatewayDepth".equalsIgnoreCase(normalizedType)) {
			updateOrderFlowDepth(resolvedSymbol, payloadJson, eventTime);
			latestMarketEventAt = eventTime;
			return;
		}
		double price = realtimePriceFromPayload(payloadJson);
		if (price <= 0.0) {
			return;
		}
		double volume = realtimeVolumeFromPayload(payloadJson);
		if ("GatewayQuote".equalsIgnoreCase(normalizedType)) {
			updateOrderFlowQuote(resolvedSymbol, payloadJson, eventTime);
		} else if ("GatewayTrade".equalsIgnoreCase(normalizedType)) {
			updateOrderFlowTrade(resolvedSymbol, payloadJson, eventTime);
		}
		latestPriceBySymbol.put(resolvedSymbol, Double.valueOf(price));
		latestMarketEventAt = eventTime;
		for (int index = 0; index < CACHE_TIMEFRAMES.length; index++) {
			String timeframe = CACHE_TIMEFRAMES[index];
			String bucketTime = candleBucketTime(eventTime, timeframe);
			String key = cacheKey(resolvedSymbol, timeframe);
			synchronized (LiveRuntimeState.class) {
				LiveCandle candle = currentCandleBySymbolTimeframe.get(key);
				if (candle == null || !bucketTime.equals(candle.time)) {
					candle = new LiveCandle(bucketTime, eventType, price);
					currentCandleBySymbolTimeframe.put(key, candle);
				}
				candle.update(eventType, price, volume);
			}
		}
	}

	public static OrderFlowSnapshot orderFlowSnapshot(String symbol) {
		String normalizedSymbol = normalizeRealtimeSymbol(symbol);
		OrderFlowBook book = orderFlowBySymbol.get(normalizedSymbol);
		if (book == null) {
			return OrderFlowSnapshot.empty(normalizedSymbol);
		}
		synchronized (book) {
			book.pruneTape(System.currentTimeMillis());
			return book.snapshot(normalizedSymbol);
		}
	}

	public static String getOrderFlowJson(String symbols) {
		List<String> symbolList = parseSymbols(cleanOrDefault(symbols, DEFAULT_SYMBOLS));
		StringBuilder json = new StringBuilder("{");
		for (int index = 0; index < symbolList.size(); index++) {
			String symbol = symbolList.get(index);
			if (index > 0) json.append(",");
			json.append(jsonString(symbol)).append(":").append(orderFlowSnapshot(symbol).toJson());
		}
		json.append("}");
		return json.toString();
	}

	public static String getBrokerPositionsJson() {
		return positionsJson();
	}

	private static void updateOrderFlowQuote(String symbol, String payloadJson, String eventTime) {
		double bid = jsonFirstNumber(payloadJson, new String[] { "bid", "Bid", "bestBid", "BestBid", "bidPrice", "BidPrice", "bp" }, 0.0);
		double ask = jsonFirstNumber(payloadJson, new String[] { "ask", "Ask", "bestAsk", "BestAsk", "askPrice", "AskPrice", "ap" }, 0.0);
		if (bid <= 0.0 && ask <= 0.0) {
			return;
		}
		OrderFlowBook book = orderFlowBookFor(symbol);
		synchronized (book) {
			if (bid > 0.0) book.bestBid = bid;
			if (ask > 0.0) book.bestAsk = ask;
			book.lastUpdatedAt = cleanOrDefault(eventTime, displayTime());
			book.lastUpdatedMillis = System.currentTimeMillis();
		}
	}

	private static void updateOrderFlowDepth(String symbol, String payloadJson, String eventTime) {
		List<String> depthEntries = extractJsonArrayObjects(payloadJson);
		if (!depthEntries.isEmpty()) {
			for (int index = 0; index < depthEntries.size(); index++) {
				updateSingleOrderFlowDepth(symbol, depthEntries.get(index), eventTime);
			}
			return;
		}
		updateSingleOrderFlowDepth(symbol, payloadJson, eventTime);
	}

	private static void updateSingleOrderFlowDepth(String symbol, String payloadJson, String eventTime) {
		double price = jsonFirstNumber(payloadJson, new String[] { "price", "Price", "p" }, 0.0);
		if (price <= 0.0) {
			return;
		}
		int type = (int) Math.round(jsonFirstNumber(payloadJson, new String[] { "type", "Type", "domType" }, 0.0));
		double rawVolume = orderFlowDepthVolume(payloadJson);
		OrderFlowBook book = orderFlowBookFor(symbol);
		synchronized (book) {
			if (type == 6) {
				book.bidLevels.clear();
				book.askLevels.clear();
			} else if (isBidDepthType(type)) {
				putDepthLevel(book.bidLevels, price, rawVolume);
				if (type == 4 || type == 9) book.bestBid = price;
			} else if (isAskDepthType(type)) {
				putDepthLevel(book.askLevels, price, rawVolume);
				if (type == 3 || type == 10) book.bestAsk = price;
			}
			book.trimDepth();
			book.lastUpdatedAt = cleanOrDefault(eventTime, displayTime());
			book.lastUpdatedMillis = System.currentTimeMillis();
		}
	}

	private static double orderFlowDepthVolume(String payloadJson) {
		double currentVolume = jsonFirstNumber(payloadJson, new String[] { "currentVolume", "CurrentVolume" }, Double.NaN);
		if (!Double.isNaN(currentVolume) && currentVolume > 0.0) {
			return currentVolume;
		}
		double displayedVolume = jsonFirstNumber(payloadJson, new String[] { "volume", "Volume", "size", "Size" }, Double.NaN);
		if (!Double.isNaN(displayedVolume)) {
			return Math.max(0.0, displayedVolume);
		}
		return Double.isNaN(currentVolume) ? 0.0 : Math.max(0.0, currentVolume);
	}

	private static void updateOrderFlowTrade(String symbol, String payloadJson, String eventTime) {
		double volume = Math.max(1.0, realtimeVolumeFromPayload(payloadJson));
		int type = (int) Math.round(jsonFirstNumber(payloadJson, new String[] { "type", "Type", "tradeType" }, -1.0));
		double signedVolume = type == 1 ? -volume : volume;
		OrderFlowBook book = orderFlowBookFor(symbol);
		synchronized (book) {
			book.tape.addLast(new TapeEvent(System.currentTimeMillis(), signedVolume));
			book.cvd += signedVolume;
			book.pruneTape(System.currentTimeMillis());
			book.lastUpdatedAt = cleanOrDefault(eventTime, displayTime());
			book.lastUpdatedMillis = System.currentTimeMillis();
		}
	}

	private static OrderFlowBook orderFlowBookFor(String symbol) {
		String normalizedSymbol = normalizeRealtimeSymbol(symbol);
		OrderFlowBook existing = orderFlowBySymbol.get(normalizedSymbol);
		if (existing != null) {
			return existing;
		}
		OrderFlowBook created = new OrderFlowBook();
		OrderFlowBook previous = orderFlowBySymbol.putIfAbsent(normalizedSymbol, created);
		return previous == null ? created : previous;
	}

	private static boolean isBidDepthType(int type) {
		return type == 2 || type == 4 || type == 9;
	}

	private static boolean isAskDepthType(int type) {
		return type == 1 || type == 3 || type == 10;
	}

	private static void putDepthLevel(TreeMap<Double, Double> levels, double price, double volume) {
		if (volume <= 0.0) {
			levels.remove(Double.valueOf(price));
		} else {
			levels.put(Double.valueOf(price), Double.valueOf(volume));
		}
	}

	private static void recordUserEvent(String eventType, String accountId, String contractId, String symbol, String payloadJson, String receivedAt) {
		String normalizedType = cleanOrDefault(eventType, "");
		String eventTime = cleanOrDefault(receivedAt, displayTime());
		String cleanAccountId = firstNonBlank(accountId, jsonText(payloadJson, "accountId", ""));
		if (cleanAccountId.length() > 0) {
			brokerAccountId = cleanAccountId;
		}
		latestBrokerEventAt = eventTime;
		if ("GatewayUserAccount".equalsIgnoreCase(normalizedType)) {
			updateAccountFromUserEvent(payloadJson);
		} else if ("GatewayUserPosition".equalsIgnoreCase(normalizedType)) {
			updatePositionFromUserEvent(cleanAccountId, contractId, symbol, payloadJson, eventTime);
		} else if ("GatewayUserOrder".equalsIgnoreCase(normalizedType)) {
			updateOrderFromUserEvent(cleanAccountId, contractId, symbol, payloadJson, eventTime);
		} else if ("GatewayUserTrade".equalsIgnoreCase(normalizedType)) {
			updateTradeFromUserEvent(cleanAccountId, contractId, symbol, payloadJson, eventTime);
		}
	}

	private static void updateAccountFromUserEvent(String payloadJson) {
		double eventBalance = jsonFirstNumber(payloadJson, new String[] { "balance", "currentBalance", "cashBalance", "equity" }, Double.NaN);
		double eventCurrentPnl = jsonFirstNumber(payloadJson, new String[] { "currentPnl", "profitAndLoss", "pnl" }, Double.NaN);
		double eventRealizedPnl = jsonFirstNumber(payloadJson, new String[] { "realizedPnl", "closedTradePnl" }, Double.NaN);
		double eventUnrealizedPnl = jsonFirstNumber(payloadJson, new String[] { "unrealizedPnl", "openPnl" }, Double.NaN);
		double eventDrawdown = jsonFirstNumber(payloadJson, new String[] { "drawdown", "maxDrawdown", "currentDrawdown" }, Double.NaN);
		if (!Double.isNaN(eventBalance)) currentBalance = eventBalance;
		if (!Double.isNaN(eventCurrentPnl)) currentPnl = eventCurrentPnl;
		if (!Double.isNaN(eventRealizedPnl)) realizedPnl = eventRealizedPnl;
		if (!Double.isNaN(eventUnrealizedPnl)) unrealizedPnl = eventUnrealizedPnl;
		if (!Double.isNaN(eventDrawdown)) drawdown = Math.abs(eventDrawdown);
		if (!Double.isNaN(accountSize) && accountSize > 0.0 && !Double.isNaN(currentPnl)) {
			returnPct = (currentPnl / accountSize) * 100.0;
		}
	}

	private static void updatePositionFromUserEvent(String accountId, String contractId, String symbol, String payloadJson, String eventTime) {
		String normalizedSymbol = normalizeRealtimeSymbol(firstNonBlank(symbol, jsonText(payloadJson, "symbolName", ""), jsonText(payloadJson, "symbol", ""), contractId));
		String key = firstNonBlank(jsonText(payloadJson, "id", ""), contractId, normalizedSymbol);
		if (key.length() == 0) {
			return;
		}
		int contracts = (int) Math.round(Math.abs(jsonFirstNumber(payloadJson, new String[] { "contracts", "size", "quantity", "qty", "positionSize" }, 0.0)));
		if (contracts <= 0) {
			brokerPositionsByKey.remove(key);
			return;
		}
		String side = normalizePositionSide(payloadJson);
		double entryPrice = jsonFirstNumber(payloadJson, new String[] { "entryPrice", "averagePrice", "avgPrice", "price" }, 0.0);
		double positionPnl = jsonFirstNumber(payloadJson, new String[] { "unrealizedPnl", "profitAndLoss", "openPnl", "pnl" }, 0.0);
		String json = "{"
			+ "\"id\":" + jsonNumberOrString(firstNonBlank(jsonText(payloadJson, "id", ""), key)) + ","
			+ "\"accountId\":" + jsonString(firstNonBlank(accountId, jsonText(payloadJson, "accountId", ""))) + ","
			+ "\"contractId\":" + jsonString(firstNonBlank(contractId, jsonText(payloadJson, "contractId", ""))) + ","
			+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
			+ "\"side\":" + jsonString(side) + ","
			+ "\"contracts\":" + contracts + ","
			+ "\"averagePrice\":" + numberOrZero(entryPrice) + ","
			+ "\"entryPrice\":" + numberOrZero(entryPrice) + ","
			+ "\"pnl\":" + numberOrZero(positionPnl) + ","
			+ "\"createdAt\":" + jsonString(firstNonBlank(jsonText(payloadJson, "creationTimestamp", ""), jsonText(payloadJson, "createdAt", ""), eventTime))
			+ "}";
		brokerPositionsByKey.put(key, json);
		openTrades = brokerPositionsByKey.size();
	}

	private static void updateOrderFromUserEvent(String accountId, String contractId, String symbol, String payloadJson, String eventTime) {
		String orderId = firstNonBlank(jsonText(payloadJson, "id", ""), jsonText(payloadJson, "orderId", ""), jsonText(payloadJson, "brokerOrderId", ""));
		String key = firstNonBlank(orderId, contractId, symbol);
		if (key.length() == 0) {
			return;
		}
		String rawStatus = firstNonBlank(jsonText(payloadJson, "status", ""), jsonText(payloadJson, "orderStatus", ""));
		if (isTerminalOrderStatus(rawStatus)) {
			brokerOrdersByKey.remove(key);
			return;
		}
		String normalizedSymbol = normalizeRealtimeSymbol(firstNonBlank(symbol, jsonText(payloadJson, "symbolName", ""), jsonText(payloadJson, "symbol", ""), contractId));
		String customTag = firstNonBlank(jsonText(payloadJson, "customTag", ""), jsonText(payloadJson, "tag", ""), jsonText(payloadJson, "text", ""));
		String json = "{"
			+ "\"id\":" + jsonNumberOrString(firstNonBlank(orderId, key)) + ","
			+ "\"brokerOrderId\":" + jsonNumberOrString(firstNonBlank(orderId, key)) + ","
			+ "\"accountId\":" + jsonString(firstNonBlank(accountId, jsonText(payloadJson, "accountId", ""))) + ","
			+ "\"contractId\":" + jsonString(firstNonBlank(contractId, jsonText(payloadJson, "contractId", ""))) + ","
			+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
			+ "\"side\":" + jsonString(firstNonBlank(jsonText(payloadJson, "side", ""), jsonText(payloadJson, "orderSide", ""))) + ","
			+ "\"status\":" + jsonString(orderStatusLabel(rawStatus)) + ","
			+ "\"rawStatus\":" + jsonString(rawStatus) + ","
			+ "\"orderType\":" + jsonString(firstNonBlank(jsonText(payloadJson, "orderType", ""), jsonText(payloadJson, "type", ""))) + ","
			+ "\"customTag\":" + jsonString(customTag) + ","
			+ "\"price\":" + numberOrZero(jsonFirstNumber(payloadJson, new String[] { "price", "limitPrice", "stopPrice" }, 0.0)) + ","
			+ "\"createdAt\":" + jsonString(firstNonBlank(jsonText(payloadJson, "creationTimestamp", ""), jsonText(payloadJson, "createdAt", ""), eventTime))
			+ "}";
		brokerOrdersByKey.put(key, json);
	}

	private static void updateTradeFromUserEvent(String accountId, String contractId, String symbol, String payloadJson, String eventTime) {
		String normalizedSymbol = normalizeRealtimeSymbol(firstNonBlank(symbol, jsonText(payloadJson, "symbolName", ""), jsonText(payloadJson, "symbol", ""), contractId));
		double grossPnl = jsonFirstNumber(payloadJson, new String[] { "profitAndLoss", "grossPnl", "pnl" }, Double.NaN);
		double fees = jsonFirstNumber(payloadJson, new String[] { "fees", "commission" }, 0.0);
		double netPnl = Double.isNaN(grossPnl) ? 0.0 : grossPnl - fees;
		String orderId = firstNonBlank(jsonText(payloadJson, "orderId", ""), jsonText(payloadJson, "brokerOrderId", ""));
		String customTag = firstNonBlank(jsonText(payloadJson, "customTag", ""), jsonText(payloadJson, "tag", ""), jsonText(payloadJson, "text", ""));
		String json = "{"
			+ "\"id\":" + jsonNumberOrString(firstNonBlank(jsonText(payloadJson, "id", ""), jsonText(payloadJson, "tradeId", ""), eventTime)) + ","
			+ "\"accountId\":" + jsonString(firstNonBlank(accountId, jsonText(payloadJson, "accountId", ""))) + ","
			+ "\"contractId\":" + jsonString(firstNonBlank(contractId, jsonText(payloadJson, "contractId", ""))) + ","
			+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
			+ "\"side\":" + jsonString(normalizeTradeSide(payloadJson)) + ","
			+ "\"contracts\":" + Math.max(0, (int) Math.round(Math.abs(jsonFirstNumber(payloadJson, new String[] { "contracts", "size", "quantity", "qty" }, 0.0)))) + ","
			+ "\"price\":" + numberOrZero(jsonFirstNumber(payloadJson, new String[] { "price", "fillPrice" }, 0.0)) + ","
			+ "\"fees\":" + numberOrZero(fees) + ","
			+ "\"grossPnl\":" + numberOrZero(grossPnl) + ","
			+ "\"pnl\":" + numberOrZero(netPnl) + ","
			+ "\"closed\":" + (!Double.isNaN(grossPnl)) + ","
			+ "\"orderId\":" + jsonNumberOrString(orderId) + ","
			+ "\"brokerOrderId\":" + jsonNumberOrString(orderId) + ","
			+ "\"customTag\":" + jsonString(customTag) + ","
			+ "\"createdAt\":" + jsonString(firstNonBlank(jsonText(payloadJson, "creationTimestamp", ""), jsonText(payloadJson, "createdAt", ""), eventTime))
			+ "}";
		synchronized (brokerTrades) {
			brokerTrades.addFirst(json);
			while (brokerTrades.size() > MAX_RECENT_TRADES) {
				brokerTrades.removeLast();
			}
		}
		if (!Double.isNaN(grossPnl)) {
			numberOfTrades = Math.max(0, numberOfTrades) + 1;
			realizedPnl = sumIfFinite(realizedPnl, netPnl);
			currentPnl = sumIfFinite(realizedPnl, unrealizedPnl);
		}
	}

	private static MetricChecks buildMetricChecks(
		boolean realtimeRunning,
		boolean feedFresh,
		long feedStaleSeconds,
		int symbolCount,
		int symbolMarkCount,
		int candleMarkCount,
		boolean brokerAccountMatched,
		String expectedAccountId,
		String cachedBrokerAccountId,
		double displayCurrentBalance,
		double displayCurrentPnl,
		String positionsJson,
		String ordersJson,
		String tradesJson
	) {
		MetricChecks checks = new MetricChecks();
		checks.realtimeRunning = realtimeRunning;
		checks.feedFresh = feedFresh;
		checks.feedStaleSeconds = feedStaleSeconds;
		checks.symbolCount = symbolCount;
		checks.symbolMarkCount = symbolMarkCount;
		checks.candleMarkCount = candleMarkCount;
		checks.brokerAccountMatched = brokerAccountMatched;
		checks.expectedAccountId = cleanOrDefault(expectedAccountId, "");
		checks.cachedBrokerAccountId = cleanOrDefault(cachedBrokerAccountId, "");
		checks.positionCount = countJsonArrayObjects(positionsJson);
		checks.orderCount = countJsonArrayObjects(ordersJson);
		checks.tradeCount = countJsonArrayObjects(tradesJson);
		checks.brokerAccountReady = brokerAccountMatched && (!Double.isNaN(displayCurrentBalance) || !Double.isNaN(displayCurrentPnl));
		checks.brokerSyncAgeSeconds = secondsSinceDisplayTime(lastBrokerSyncAt);
		checks.brokerEventAgeSeconds = secondsSinceDisplayTime(latestBrokerEventAt);
		checks.marketOk = !realtimeRunning || (feedFresh && symbolMarkCount > 0);
		checks.symbolMarksOk = !realtimeRunning || symbolCount <= 0 || symbolMarkCount >= symbolCount;
		checks.currentCandlesOk = !realtimeRunning || symbolCount <= 0 || candleMarkCount >= symbolCount;
		checks.brokerSyncOk = brokerAccountMatched && (checks.brokerAccountReady || checks.brokerEventAgeSeconds >= 0L || checks.brokerSyncAgeSeconds >= 0L);
		checks.hasError = realtimeRunning && (!checks.marketOk || !checks.symbolMarksOk || !checks.currentCandlesOk);
		checks.hasWarn = !checks.hasError && (!brokerAccountMatched || (realtimeRunning && (!checks.brokerAccountReady || !checks.brokerSyncOk)));
		return checks;
	}

	private static void logMetricChecks(MetricChecks checks) {
		if (checks == null) {
			return;
		}
		String level = checks.hasError ? "ERROR" : (checks.hasWarn ? "WARN" : "OK");
		String key = level
			+ "|" + checks.feedFresh
			+ "|" + checks.symbolMarkCount
			+ "|" + checks.candleMarkCount
			+ "|" + checks.brokerAccountMatched
			+ "|" + checks.expectedAccountId
			+ "|" + checks.cachedBrokerAccountId
			+ "|" + checks.brokerAccountReady
			+ "|" + checks.brokerSyncOk
			+ "|" + checks.positionCount
			+ "|" + checks.orderCount;
		long now = System.currentTimeMillis();
		Long previousLogAt = lastMetricCheckLogAtByKey.get(key);
		if (previousLogAt != null && now - previousLogAt.longValue() < 10000L) {
			return;
		}
		lastMetricCheckLogAtByKey.put(key, now);
		String message = "Live marks metric check " + level
			+ " feedFresh=" + checks.feedFresh
			+ " feedStaleSeconds=" + checks.feedStaleSeconds
			+ " symbolMarks=" + checks.symbolMarkCount + "/" + checks.symbolCount
			+ " currentCandles=" + checks.candleMarkCount + "/" + checks.symbolCount
			+ " brokerAccountMatched=" + checks.brokerAccountMatched
			+ " expectedAccountId=" + cleanForLog(checks.expectedAccountId)
			+ " cachedBrokerAccountId=" + cleanForLog(checks.cachedBrokerAccountId)
			+ " brokerAccountReady=" + checks.brokerAccountReady
			+ " brokerSyncAgeSeconds=" + checks.brokerSyncAgeSeconds
			+ " brokerEventAgeSeconds=" + checks.brokerEventAgeSeconds
			+ " positions=" + checks.positionCount
			+ " orders=" + checks.orderCount
			+ " trades=" + checks.tradeCount;
		if (checks.hasError || checks.hasWarn) {
			System.err.println(message);
		} else {
			System.out.println(message);
		}
	}

	private static String positionsJson() {
		if (!brokerPositionsByKey.isEmpty()) {
			return mapValuesJson(brokerPositionsByKey);
		}
		return jsonObjectOrArrayOrDefault(brokerPositionsJson, "[]");
	}

	private static String ordersJson() {
		if (!brokerOrdersByKey.isEmpty()) {
			return mapValuesJson(brokerOrdersByKey);
		}
		return jsonObjectOrArrayOrDefault(brokerOrdersJson, "[]");
	}

	private static String tradesJson() {
		synchronized (brokerTrades) {
			if (!brokerTrades.isEmpty()) {
				StringBuilder json = new StringBuilder("[");
				int index = 0;
				for (String tradeJson : brokerTrades) {
					if (index > 0) json.append(",");
					json.append(jsonObjectOrArrayOrDefault(tradeJson, "{}"));
					index++;
				}
				json.append("]");
				return json.toString();
			}
		}
		return jsonObjectOrArrayOrDefault(brokerTradesJson, "[]");
	}

	private static String mapValuesJson(ConcurrentHashMap<String, String> map) {
		StringBuilder json = new StringBuilder("[");
		int index = 0;
		for (String value : map.values()) {
			if (index > 0) json.append(",");
			json.append(jsonObjectOrArrayOrDefault(value, "{}"));
			index++;
		}
		json.append("]");
		return json.toString();
	}

	private static String filterJsonArrayByAccount(String arrayJson, String accountId) {
		String cleanAccountId = cleanOrDefault(accountId, "");
		if (cleanAccountId.length() == 0) {
			return jsonObjectOrArrayOrDefault(arrayJson, "[]");
		}
		List<String> objects = extractJsonArrayObjects(arrayJson);
		StringBuilder json = new StringBuilder("[");
		int kept = 0;
		for (int index = 0; index < objects.size(); index++) {
			String object = objects.get(index);
			if (!cleanAccountId.equals(jsonText(object, "accountId", ""))) {
				continue;
			}
			if (kept > 0) json.append(",");
			json.append(jsonObjectOrArrayOrDefault(object, "{}"));
			kept++;
		}
		json.append("]");
		return json.toString();
	}

	private static double calculateUnrealizedPnlFromPositions(String positionsJson) {
		List<String> positions = extractJsonArrayObjects(positionsJson);
		if (positions.isEmpty()) {
			return Double.NaN;
		}
		double total = 0.0;
		for (int index = 0; index < positions.size(); index++) {
			String position = positions.get(index);
			String symbol = normalizeRealtimeSymbol(jsonText(position, "symbol", ""));
			Double mark = latestPriceBySymbol.get(symbol);
			double entry = jsonFirstNumber(position, new String[] { "entryPrice", "averagePrice" }, 0.0);
			int contracts = (int) Math.round(jsonFirstNumber(position, new String[] { "contracts" }, 0.0));
			String side = jsonText(position, "side", "LONG");
			if (mark != null && mark.doubleValue() > 0.0 && entry > 0.0 && contracts > 0) {
				total += futuresPnl(symbol, side, entry, mark.doubleValue(), contracts);
			} else {
				total += jsonFirstNumber(position, new String[] { "unrealizedPnl", "pnl" }, 0.0);
			}
		}
		return total;
	}

	private static double futuresPnl(String symbol, String side, double entry, double mark, int contracts) {
		double pointValue = pointValue(symbol);
		double move = "SHORT".equalsIgnoreCase(side) ? entry - mark : mark - entry;
		return round(move * pointValue * contracts);
	}

	private static double pointValue(String symbol) {
		String normalized = normalizeRealtimeSymbol(symbol);
		if ("ES".equals(normalized)) return 50.0;
		if ("NQ".equals(normalized)) return 20.0;
		if ("MES".equals(normalized)) return 5.0;
		if ("MNQ".equals(normalized)) return 2.0;
		if ("M2K".equals(normalized)) return 5.0;
		if ("MYM".equals(normalized)) return 0.5;
		if ("GC".equals(normalized)) return 100.0;
		if ("MGC".equals(normalized)) return 10.0;
		if ("MCL".equals(normalized)) return 100.0;
		return 1.0;
	}

	private static String cacheKey(String symbol, String timeframe) {
		return normalizeRealtimeSymbol(symbol) + "|" + normalizeTimeframe(timeframe);
	}

	private static String candleBucketTime(String displayTime, String timeframe) {
		LocalDateTime parsed = parseDisplayLocalDateTime(displayTime);
		if (parsed == null) {
			parsed = ZonedDateTime.now(NEW_YORK_ZONE).toLocalDateTime();
		}
		String normalizedTimeframe = normalizeTimeframe(timeframe);
		if ("1h".equals(normalizedTimeframe)) {
			parsed = parsed.withMinute(0).withSecond(0).withNano(0);
		} else {
			int minutes = "30m".equals(normalizedTimeframe) ? 30 : ("15m".equals(normalizedTimeframe) ? 15 : ("5m".equals(normalizedTimeframe) ? 5 : 1));
			parsed = parsed.withMinute((parsed.getMinute() / minutes) * minutes).withSecond(0).withNano(0);
		}
		return parsed.format(DISPLAY_TIME_FORMAT);
	}

	private static String normalizeTimeframe(String timeframe) {
		String normalized = cleanOrDefault(timeframe, "1m").toLowerCase(Locale.US);
		if ("5".equals(normalized) || "5min".equals(normalized) || "5m".equals(normalized)) return "5m";
		if ("15".equals(normalized) || "15min".equals(normalized) || "15m".equals(normalized)) return "15m";
		if ("30".equals(normalized) || "30min".equals(normalized) || "30m".equals(normalized)) return "30m";
		if ("60".equals(normalized) || "60min".equals(normalized) || "1hour".equals(normalized) || "1h".equals(normalized)) return "1h";
		return "1m";
	}

	private static List<String> parseSymbols(String symbols) {
		List<String> values = new ArrayList<String>();
		String[] parts = cleanOrDefault(symbols, DEFAULT_SYMBOLS).split(",");
		for (int index = 0; index < parts.length; index++) {
			String symbol = normalizeRealtimeSymbol(parts[index]);
			if (symbol.length() > 0 && !values.contains(symbol)) {
				values.add(symbol);
			}
		}
		if (values.isEmpty()) {
			String[] defaults = DEFAULT_SYMBOLS.split(",");
			for (int index = 0; index < defaults.length; index++) {
				values.add(defaults[index]);
			}
		}
		return values;
	}

	private static String normalizeRealtimeSymbol(String value) {
		String normalized = cleanOrDefault(value, "").toUpperCase(Locale.US).replace("/", "");
		if (normalized.length() == 0) {
			return "";
		}
		if (normalized.startsWith("CON.F.US.")) {
			String[] parts = normalized.split("\\.");
			if (parts.length >= 4) normalized = parts[3];
		} else if (normalized.startsWith("F.US.")) {
			String[] parts = normalized.split("\\.");
			if (parts.length >= 3) normalized = parts[2];
		}
		int dotIndex = normalized.indexOf('.');
		if (dotIndex > 0) {
			normalized = normalized.substring(0, dotIndex);
		}
		if ("EP".equals(normalized)) return "ES";
		if ("ENQ".equals(normalized)) return "NQ";
		if ("GCE".equals(normalized)) return "GC";
		if ("MESH".equals(normalized) || normalized.startsWith("MES")) return "MES";
		if ("MNQH".equals(normalized) || normalized.startsWith("MNQ")) return "MNQ";
		if ("M2KH".equals(normalized) || normalized.startsWith("M2K")) return "M2K";
		if ("MYMH".equals(normalized) || normalized.startsWith("MYM")) return "MYM";
		if ("MGCH".equals(normalized) || normalized.startsWith("MGC")) return "MGC";
		if (normalized.startsWith("MCL")) return "MCL";
		if (normalized.startsWith("ES")) return "ES";
		if (normalized.startsWith("NQ")) return "NQ";
		if (normalized.startsWith("GC")) return "GC";
		return normalized;
	}

	private static double realtimePriceFromPayload(String payloadJson) {
		double direct = jsonFirstNumber(payloadJson, new String[] {
			"price", "Price", "lastPrice", "LastPrice", "tradePrice", "TradePrice",
			"last", "Last", "close", "Close", "p"
		}, 0.0);
		if (direct > 0.0) {
			return direct;
		}
		double bid = jsonFirstNumber(payloadJson, new String[] { "bid", "Bid", "bestBid", "BestBid", "bidPrice", "BidPrice", "bp" }, 0.0);
		double ask = jsonFirstNumber(payloadJson, new String[] { "ask", "Ask", "bestAsk", "BestAsk", "askPrice", "AskPrice", "ap" }, 0.0);
		if (bid > 0.0 && ask > 0.0) {
			return (bid + ask) / 2.0;
		}
		return Math.max(bid, ask);
	}

	private static double realtimeVolumeFromPayload(String payloadJson) {
		return jsonFirstNumber(payloadJson, new String[] { "volume", "Volume", "size", "Size", "qty", "Qty", "quantity", "Quantity", "tradeSize", "TradeSize" }, 0.0);
	}

	private static String normalizePositionSide(String payloadJson) {
		String direct = cleanOrDefault(firstNonBlank(jsonText(payloadJson, "side", ""), jsonText(payloadJson, "positionSide", "")), "").toUpperCase(Locale.US);
		if (direct.contains("SHORT") || "SELL".equals(direct) || "2".equals(direct)) return "SHORT";
		return "LONG";
	}

	private static String normalizeTradeSide(String payloadJson) {
		String direct = cleanOrDefault(firstNonBlank(jsonText(payloadJson, "side", ""), jsonText(payloadJson, "tradeSide", "")), "").toUpperCase(Locale.US);
		if (direct.contains("SELL") || direct.contains("SHORT") || "2".equals(direct)) return "SELL";
		if (direct.contains("BUY") || direct.contains("LONG") || "1".equals(direct)) return "BUY";
		return direct;
	}

	private static boolean isTerminalOrderStatus(String status) {
		String normalized = cleanOrDefault(status, "").toUpperCase(Locale.US);
		double numericStatus = parseDouble(normalized, Double.NaN);
		if (!Double.isNaN(numericStatus)) {
			int statusCode = (int) Math.round(numericStatus);
			return statusCode == 2 || statusCode == 3;
		}
		return normalized.contains("FILLED")
			|| normalized.contains("CANCEL")
			|| normalized.contains("REJECT")
			|| normalized.contains("COMPLETE")
			|| normalized.contains("EXPIRED");
	}

	private static String orderStatusLabel(String status) {
		String normalized = cleanOrDefault(status, "").toUpperCase(Locale.US);
		double numericStatus = parseDouble(normalized, Double.NaN);
		if (!Double.isNaN(numericStatus)) {
			int statusCode = (int) Math.round(numericStatus);
			if (statusCode == 1) return "OPEN";
			if (statusCode == 2) return "FILLED";
			if (statusCode == 3) return "CANCELED";
		}
		return cleanOrDefault(status, "");
	}

	private static String displayTime() {
		return ZonedDateTime.now(NEW_YORK_ZONE).toLocalDateTime().format(SERVER_TIME_FORMAT);
	}

	private static String serverTime() {
		return ZonedDateTime.now(NEW_YORK_ZONE).toLocalDateTime().format(SERVER_TIME_FORMAT);
	}

	private static LocalDateTime parseDisplayLocalDateTime(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String clean = value.trim();
		try {
			return LocalDateTime.parse(clean, SERVER_TIME_FORMAT);
		} catch (Exception ignored) {
		}
		try {
			return LocalDateTime.parse(clean, DISPLAY_TIME_FORMAT);
		} catch (Exception ignored) {
		}
		try {
			return LocalDateTime.ofInstant(Instant.parse(clean), NEW_YORK_ZONE);
		} catch (Exception ignored) {
		}
		try {
			return OffsetDateTime.parse(clean).atZoneSameInstant(NEW_YORK_ZONE).toLocalDateTime();
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

	private static double jsonFirstNumber(String json, String[] keys, double defaultValue) {
		if (keys == null) return defaultValue;
		for (int index = 0; index < keys.length; index++) {
			double value = jsonNumberFlexible(json, keys[index], Double.NaN);
			if (!Double.isNaN(value)) return value;
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
		if (index >= json.length()) {
			return defaultValue;
		}
		if (json.charAt(index) != '"') {
			int end = index;
			while (end < json.length()) {
				char ch = json.charAt(end);
				if (ch == ',' || ch == '}' || ch == ']') break;
				end++;
			}
			return json.substring(index, end).trim();
		}
		StringBuilder value = new StringBuilder();
		boolean escaped = false;
		for (int cursor = index + 1; cursor < json.length(); cursor++) {
			char ch = json.charAt(cursor);
			if (escaped) {
				if (ch == 'n') value.append('\n');
				else if (ch == 'r') value.append('\r');
				else value.append(ch);
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

	private static String jsonArrayOrDefault(String json, String key, String defaultValue) {
		if (json == null || key == null || key.trim().isEmpty()) {
			return defaultValue;
		}
		String needle = "\"" + key + "\":";
		int start = json.indexOf(needle);
		if (start < 0) {
			return defaultValue;
		}
		int bracketStart = json.indexOf('[', start + needle.length());
		if (bracketStart < 0) {
			return defaultValue;
		}
		int bracketEnd = matchingBracket(json, bracketStart, '[', ']');
		if (bracketEnd <= bracketStart) {
			return defaultValue;
		}
		return json.substring(bracketStart, bracketEnd + 1);
	}

	private static List<String> extractJsonArrayObjects(String arrayJson) {
		List<String> objects = new ArrayList<String>();
		String trimmed = cleanOrDefault(arrayJson, "");
		if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
			return objects;
		}
		int index = 0;
		while (index < trimmed.length()) {
			int objectStart = trimmed.indexOf('{', index);
			if (objectStart < 0) break;
			int objectEnd = matchingBracket(trimmed, objectStart, '{', '}');
			if (objectEnd <= objectStart) break;
			objects.add(trimmed.substring(objectStart, objectEnd + 1));
			index = objectEnd + 1;
		}
		return objects;
	}

	private static int matchingBracket(String json, int start, char open, char close) {
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int index = start; index < json.length(); index++) {
			char ch = json.charAt(index);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (ch == '\\') {
				escaped = true;
				continue;
			}
			if (ch == '"') {
				inString = !inString;
				continue;
			}
			if (inString) {
				continue;
			}
			if (ch == open) {
				depth++;
			} else if (ch == close) {
				depth--;
				if (depth == 0) {
					return index;
				}
			}
		}
		return -1;
	}

	private static int countJsonArrayObjects(String arrayJson) {
		return extractJsonArrayObjects(arrayJson).size();
	}

	private static double sumIfFinite(double first, double second) {
		double total = 0.0;
		boolean hasValue = false;
		if (!Double.isNaN(first)) {
			total += first;
			hasValue = true;
		}
		if (!Double.isNaN(second)) {
			total += second;
			hasValue = true;
		}
		return hasValue ? total : Double.NaN;
	}

	private static String jsonNumberOrString(String value) {
		String clean = cleanOrDefault(value, "");
		if (clean.length() == 0) {
			return "0";
		}
		for (int index = 0; index < clean.length(); index++) {
			char ch = clean.charAt(index);
			if (!((ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.')) {
				return jsonString(clean);
			}
		}
		return clean;
	}

	private static String jsonObjectOrArrayOrDefault(String value, String defaultValue) {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		String trimmed = value.trim();
		if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
			return trimmed;
		}
		return defaultValue;
	}

	private static String numberOrNull(double value) {
		return Double.isNaN(value) || Double.isInfinite(value) ? "null" : String.valueOf(round(value));
	}

	private static String numberOrZero(double value) {
		return Double.isNaN(value) || Double.isInfinite(value) ? "0" : String.valueOf(round(value));
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

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
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
		for (int index = 0; index < values.length; index++) {
			if (values[index] != null && !values[index].trim().isEmpty()) {
				return values[index].trim();
			}
		}
		return "";
	}

	private static String safeMessage(String message) {
		if (message == null || message.trim().isEmpty()) {
			return "unknown error";
		}
		return message.replace('\n', ' ').replace('\r', ' ').trim();
	}

	private static String cleanForLog(String value) {
		String clean = cleanOrDefault(value, "");
		return clean.length() == 0 ? "-" : safeMessage(clean);
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

	public static class OrderFlowSnapshot {
		public String symbol = "";
		public boolean available;
		public boolean fresh;
		public long ageSeconds = -1L;
		public String lastUpdatedAt = "";
		public double bestBid;
		public double bestAsk;
		public double spread;
		public double spreadTicks;
		public double topBookImbalance;
		public double depthImbalance3;
		public double depthImbalance5;
		public double depthImbalance10;
		public double aggressiveBuyVolume;
		public double aggressiveSellVolume;
		public double tapeDelta;
		public double cvd;
		public String bookFlip = "NONE";
		public String absorption = "NONE";
		public boolean liquidityVacuum;
		public double liquidityWallDistanceTicks;
		public double bidWallDistanceTicks;
		public double askWallDistanceTicks;
		public double bidStacking;
		public double askStacking;
		public String flowState = "WAITING_FOR_DEPTH";

		private static OrderFlowSnapshot empty(String symbol) {
			OrderFlowSnapshot snapshot = new OrderFlowSnapshot();
			snapshot.symbol = cleanOrDefault(symbol, "");
			return snapshot;
		}

		public String toJson() {
			return "{"
				+ "\"symbol\":" + jsonString(symbol) + ","
				+ "\"available\":" + available + ","
				+ "\"fresh\":" + fresh + ","
				+ "\"ageSeconds\":" + ageSeconds + ","
				+ "\"lastUpdatedAt\":" + jsonString(lastUpdatedAt) + ","
				+ "\"bestBid\":" + numberOrZero(bestBid) + ","
				+ "\"bestAsk\":" + numberOrZero(bestAsk) + ","
				+ "\"spread\":" + numberOrZero(spread) + ","
				+ "\"spreadTicks\":" + numberOrZero(spreadTicks) + ","
				+ "\"topBookImbalance\":" + numberOrZero(topBookImbalance) + ","
				+ "\"depthImbalance3\":" + numberOrZero(depthImbalance3) + ","
				+ "\"depthImbalance5\":" + numberOrZero(depthImbalance5) + ","
				+ "\"depthImbalance10\":" + numberOrZero(depthImbalance10) + ","
				+ "\"aggressiveBuyVolume\":" + numberOrZero(aggressiveBuyVolume) + ","
				+ "\"aggressiveSellVolume\":" + numberOrZero(aggressiveSellVolume) + ","
				+ "\"tapeDelta\":" + numberOrZero(tapeDelta) + ","
				+ "\"cvd\":" + numberOrZero(cvd) + ","
				+ "\"bookFlip\":" + jsonString(bookFlip) + ","
				+ "\"absorption\":" + jsonString(absorption) + ","
				+ "\"liquidityVacuum\":" + liquidityVacuum + ","
				+ "\"liquidityWallDistanceTicks\":" + numberOrZero(liquidityWallDistanceTicks) + ","
				+ "\"bidWallDistanceTicks\":" + numberOrZero(bidWallDistanceTicks) + ","
				+ "\"askWallDistanceTicks\":" + numberOrZero(askWallDistanceTicks) + ","
				+ "\"bidStacking\":" + numberOrZero(bidStacking) + ","
				+ "\"askStacking\":" + numberOrZero(askStacking) + ","
				+ "\"flowState\":" + jsonString(flowState)
				+ "}";
		}
	}

	private static class OrderFlowBook {
		private final TreeMap<Double, Double> bidLevels = new TreeMap<Double, Double>();
		private final TreeMap<Double, Double> askLevels = new TreeMap<Double, Double>();
		private final Deque<TapeEvent> tape = new ArrayDeque<TapeEvent>();
		private String lastUpdatedAt = "";
		private long lastUpdatedMillis;
		private double bestBid;
		private double bestAsk;
		private double cvd;
		private double previousImbalance5;
		private double previousBidVolume5;
		private double previousAskVolume5;

		private void trimDepth() {
			while (bidLevels.size() > 40) {
				bidLevels.pollFirstEntry();
			}
			while (askLevels.size() > 40) {
				askLevels.pollLastEntry();
			}
		}

		private void pruneTape(long nowMillis) {
			while (!tape.isEmpty() && nowMillis - tape.peekFirst().timeMillis > ORDER_FLOW_WINDOW_MS) {
				tape.removeFirst();
			}
		}

		private OrderFlowSnapshot snapshot(String symbol) {
			OrderFlowSnapshot snapshot = new OrderFlowSnapshot();
			snapshot.symbol = cleanOrDefault(symbol, "");
			snapshot.lastUpdatedAt = cleanOrDefault(lastUpdatedAt, "");
			snapshot.ageSeconds = lastUpdatedMillis <= 0L ? -1L : Math.max(0L, (System.currentTimeMillis() - lastUpdatedMillis) / 1000L);
			snapshot.fresh = snapshot.ageSeconds >= 0L && snapshot.ageSeconds <= ORDER_FLOW_FRESH_SECONDS;
			double depthBid = bidLevels.isEmpty() ? 0.0 : bidLevels.lastKey().doubleValue();
			double depthAsk = askLevels.isEmpty() ? 0.0 : askLevels.firstKey().doubleValue();
			double bid = bestBid > 0.0 ? bestBid : depthBid;
			double ask = bestAsk > 0.0 ? bestAsk : depthAsk;
			boolean crossedBook = bid > 0.0 && ask > 0.0 && ask < bid;
			if (crossedBook && depthBid > 0.0 && depthAsk > 0.0 && depthAsk >= depthBid) {
				bid = depthBid;
				ask = depthAsk;
				crossedBook = false;
			}
			snapshot.bestBid = bid;
			snapshot.bestAsk = ask;
			snapshot.available = !crossedBook && (bid > 0.0 || ask > 0.0 || !bidLevels.isEmpty() || !askLevels.isEmpty() || !tape.isEmpty());
			if (crossedBook) {
				snapshot.fresh = false;
				snapshot.flowState = "CROSSED_BOOK";
				return snapshot;
			}
			double tickSize = tickSizeForSymbol(snapshot.symbol);
			if (bid > 0.0 && ask > 0.0 && ask >= bid) {
				snapshot.spread = ask - bid;
				snapshot.spreadTicks = tickSize <= 0.0 ? 0.0 : snapshot.spread / tickSize;
			}
			double bidVolume1 = depthVolume(bidLevels, true, 1);
			double askVolume1 = depthVolume(askLevels, false, 1);
			double bidVolume3 = depthVolume(bidLevels, true, 3);
			double askVolume3 = depthVolume(askLevels, false, 3);
			double bidVolume5 = depthVolume(bidLevels, true, 5);
			double askVolume5 = depthVolume(askLevels, false, 5);
			double bidVolume10 = depthVolume(bidLevels, true, ORDER_FLOW_LEVELS);
			double askVolume10 = depthVolume(askLevels, false, ORDER_FLOW_LEVELS);
			snapshot.topBookImbalance = imbalance(bidVolume1, askVolume1);
			snapshot.depthImbalance3 = imbalance(bidVolume3, askVolume3);
			snapshot.depthImbalance5 = imbalance(bidVolume5, askVolume5);
			snapshot.depthImbalance10 = imbalance(bidVolume10, askVolume10);
			for (TapeEvent event : tape) {
				if (event.signedVolume >= 0.0) {
					snapshot.aggressiveBuyVolume += event.signedVolume;
				} else {
					snapshot.aggressiveSellVolume += Math.abs(event.signedVolume);
				}
			}
			snapshot.tapeDelta = snapshot.aggressiveBuyVolume - snapshot.aggressiveSellVolume;
			snapshot.cvd = cvd;
			if (previousImbalance5 < -0.20 && snapshot.depthImbalance5 > 0.20) {
				snapshot.bookFlip = "BID_FLIP";
			} else if (previousImbalance5 > 0.20 && snapshot.depthImbalance5 < -0.20) {
				snapshot.bookFlip = "ASK_FLIP";
			}
			snapshot.bidStacking = bidVolume5 - previousBidVolume5;
			snapshot.askStacking = askVolume5 - previousAskVolume5;
			double mid = bid > 0.0 && ask > 0.0 ? (bid + ask) / 2.0 : Math.max(bid, ask);
			double[] bidWall = wallDistanceTicks(bidLevels, true, mid, tickSize, bidVolume10);
			double[] askWall = wallDistanceTicks(askLevels, false, mid, tickSize, askVolume10);
			snapshot.bidWallDistanceTicks = bidWall[0];
			snapshot.askWallDistanceTicks = askWall[0];
			snapshot.liquidityWallDistanceTicks = minPositive(snapshot.bidWallDistanceTicks, snapshot.askWallDistanceTicks);
			if (snapshot.bidWallDistanceTicks > 0.0 && snapshot.bidWallDistanceTicks <= 4.0 && snapshot.aggressiveSellVolume > snapshot.aggressiveBuyVolume * 1.4) {
				snapshot.absorption = "BID_ABSORPTION";
			} else if (snapshot.askWallDistanceTicks > 0.0 && snapshot.askWallDistanceTicks <= 4.0 && snapshot.aggressiveBuyVolume > snapshot.aggressiveSellVolume * 1.4) {
				snapshot.absorption = "ASK_ABSORPTION";
			}
			snapshot.liquidityVacuum = (bidVolume5 + askVolume5) > 0.0 && (bidVolume3 + askVolume3) < Math.max(8.0, (bidVolume10 + askVolume10) * 0.18);
			if (!snapshot.available) {
				snapshot.flowState = "WAITING_FOR_DEPTH";
			} else if (snapshot.spreadTicks >= 5.0) {
				snapshot.flowState = "SPREAD_WIDE";
			} else if (snapshot.depthImbalance5 >= 0.25) {
				snapshot.flowState = "BID_HEAVY";
			} else if (snapshot.depthImbalance5 <= -0.25) {
				snapshot.flowState = "ASK_HEAVY";
			} else {
				snapshot.flowState = "BALANCED";
			}
			previousImbalance5 = snapshot.depthImbalance5;
			previousBidVolume5 = bidVolume5;
			previousAskVolume5 = askVolume5;
			return snapshot;
		}
	}

	private static class TapeEvent {
		private final long timeMillis;
		private final double signedVolume;

		private TapeEvent(long timeMillis, double signedVolume) {
			this.timeMillis = timeMillis;
			this.signedVolume = signedVolume;
		}
	}

	private static double depthVolume(TreeMap<Double, Double> levels, boolean bidSide, int maxLevels) {
		double volume = 0.0;
		int count = 0;
		Iterable<Map.Entry<Double, Double>> entries = bidSide ? levels.descendingMap().entrySet() : levels.entrySet();
		for (Map.Entry<Double, Double> entry : entries) {
			volume += Math.max(0.0, entry.getValue().doubleValue());
			count++;
			if (count >= maxLevels) {
				break;
			}
		}
		return volume;
	}

	private static double imbalance(double bidVolume, double askVolume) {
		double total = bidVolume + askVolume;
		return total <= 0.0 ? 0.0 : (bidVolume - askVolume) / total;
	}

	private static double[] wallDistanceTicks(TreeMap<Double, Double> levels, boolean bidSide, double mid, double tickSize, double topVolume) {
		if (levels.isEmpty() || mid <= 0.0 || tickSize <= 0.0) {
			return new double[] { 0.0, 0.0 };
		}
		double average = Math.max(1.0, topVolume / Math.max(1.0, Math.min(ORDER_FLOW_LEVELS, levels.size())));
		double threshold = Math.max(10.0, average * 1.8);
		double bestDistance = 0.0;
		double bestVolume = 0.0;
		Iterable<Map.Entry<Double, Double>> entries = bidSide ? levels.descendingMap().entrySet() : levels.entrySet();
		int count = 0;
		for (Map.Entry<Double, Double> entry : entries) {
			double volume = Math.max(0.0, entry.getValue().doubleValue());
			double distance = Math.abs(entry.getKey().doubleValue() - mid) / tickSize;
			if (volume >= threshold && (bestDistance <= 0.0 || distance < bestDistance)) {
				bestDistance = distance;
				bestVolume = volume;
			}
			count++;
			if (count >= ORDER_FLOW_LEVELS) {
				break;
			}
		}
		return new double[] { bestDistance, bestVolume };
	}

	private static double minPositive(double first, double second) {
		if (first <= 0.0) return Math.max(0.0, second);
		if (second <= 0.0) return first;
		return Math.min(first, second);
	}

	private static double tickSizeForSymbol(String symbol) {
		String normalized = cleanOrDefault(symbol, "").toUpperCase(Locale.US);
		if ("MGC".equals(normalized) || "GC".equals(normalized)) return 0.10;
		if ("M2K".equals(normalized)) return 0.10;
		if ("MYM".equals(normalized)) return 1.00;
		if ("MCL".equals(normalized)) return 0.01;
		return 0.25;
	}

	private static class MetricChecks {
		private boolean realtimeRunning;
		private boolean feedFresh;
		private long feedStaleSeconds;
		private int symbolCount;
		private int symbolMarkCount;
		private int candleMarkCount;
		private boolean marketOk;
		private boolean symbolMarksOk;
		private boolean currentCandlesOk;
		private boolean brokerAccountMatched;
		private String expectedAccountId;
		private String cachedBrokerAccountId;
		private boolean brokerAccountReady;
		private boolean brokerSyncOk;
		private long brokerSyncAgeSeconds;
		private long brokerEventAgeSeconds;
		private int positionCount;
		private int orderCount;
		private int tradeCount;
		private boolean hasError;
		private boolean hasWarn;

		private String toJson() {
			String brokerAccountMessage = brokerAccountMatched
				? (brokerAccountReady ? "Broker account metrics are cached." : "Broker account metrics have not populated the marks cache yet.")
				: "Broker cache is for account " + cleanForDisplay(cachedBrokerAccountId) + ", not selected account " + cleanForDisplay(expectedAccountId) + ".";
			String brokerSyncMessage = brokerAccountMatched
				? (brokerSyncOk ? "Broker sync or broker realtime event has populated the cache." : "No broker sync or broker realtime event has populated the cache yet.")
				: "Broker sync cache belongs to a different account.";
			return "{"
				+ "\"overall\":" + checkJson(!hasError && !hasWarn, hasError ? "error" : (hasWarn ? "warn" : "ok"), hasError ? "Fast live marks need attention." : (hasWarn ? "Broker/account marks need attention." : "Fast live marks checks are healthy.")) + ","
				+ "\"marketFeed\":" + checkJson(marketOk, marketOk ? "ok" : "error", realtimeRunning ? (feedFresh ? "ProjectX realtime feed is fresh." : "ProjectX realtime feed is stale or missing latest marks.") : "Realtime feed is not running.") + ","
				+ "\"symbolMarks\":" + checkJson(symbolMarksOk, symbolMarksOk ? "ok" : "error", "Latest prices cached for " + symbolMarkCount + " of " + symbolCount + " symbols.") + ","
				+ "\"currentCandles\":" + checkJson(currentCandlesOk, currentCandlesOk ? "ok" : "error", "Current candles cached for " + candleMarkCount + " of " + symbolCount + " symbols.") + ","
				+ "\"brokerAccount\":" + checkJson(brokerAccountMatched && brokerAccountReady, (brokerAccountMatched && brokerAccountReady) ? "ok" : "warn", brokerAccountMessage) + ","
				+ "\"brokerSync\":" + checkJson(brokerSyncOk, brokerSyncOk ? "ok" : "warn", brokerSyncMessage) + ","
				+ "\"counts\":{"
					+ "\"positions\":" + positionCount + ","
					+ "\"orders\":" + orderCount + ","
					+ "\"trades\":" + tradeCount + ","
					+ "\"feedStaleSeconds\":" + feedStaleSeconds + ","
					+ "\"brokerSyncAgeSeconds\":" + brokerSyncAgeSeconds + ","
					+ "\"brokerEventAgeSeconds\":" + brokerEventAgeSeconds + ","
					+ "\"brokerAccountMatched\":" + brokerAccountMatched + ","
					+ "\"expectedAccountId\":" + jsonString(expectedAccountId) + ","
					+ "\"cachedBrokerAccountId\":" + jsonString(cachedBrokerAccountId)
				+ "}"
				+ "}";
		}

		private static String cleanForDisplay(String value) {
			String clean = cleanOrDefault(value, "");
			return clean.length() == 0 ? "unknown" : clean;
		}

		private static String checkJson(boolean ok, String severity, String message) {
			return "{"
				+ "\"ok\":" + ok + ","
				+ "\"severity\":" + jsonString(severity) + ","
				+ "\"message\":" + jsonString(message)
				+ "}";
		}
	}

	private static class LiveCandle {
		private final String time;
		private String eventType;
		private double open;
		private double high;
		private double low;
		private double close;
		private double volume;
		private int events;
		private boolean live = true;

		private LiveCandle(String time, String eventType, double price) {
			this.time = cleanOrDefault(time, "");
			this.eventType = cleanOrDefault(eventType, "LIVE");
			this.open = price;
			this.high = price;
			this.low = price;
			this.close = price;
		}

		private void update(String nextEventType, double price, double nextVolume) {
			this.eventType = cleanOrDefault(nextEventType, this.eventType);
			this.high = Math.max(this.high, price);
			this.low = this.low <= 0.0 ? price : Math.min(this.low, price);
			this.close = price;
			this.volume += nextVolume > 0.0 ? nextVolume : 1.0;
			this.events++;
		}

		private String toJson() {
			return "{"
				+ "\"time\":" + jsonString(time) + ","
				+ "\"eventType\":" + jsonString(eventType) + ","
				+ "\"open\":" + numberOrZero(open) + ","
				+ "\"high\":" + numberOrZero(high) + ","
				+ "\"low\":" + numberOrZero(low) + ","
				+ "\"close\":" + numberOrZero(close) + ","
				+ "\"volume\":" + numberOrZero(volume) + ","
				+ "\"events\":" + events + ","
				+ "\"live\":" + live
				+ "}";
		}
	}
}
