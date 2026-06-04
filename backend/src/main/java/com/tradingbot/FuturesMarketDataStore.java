package com.tradingbot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class FuturesMarketDataStore {
	static final String SOURCE_LIVE_CAPTURED = "LIVE_CAPTURED";
	static final String SOURCE_DERIVED_GAP_FILL = "DERIVED_GAP_FILL";
	private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");
	private static final DateTimeFormatter DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter DISPLAY_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private FuturesMarketDataStore() {
	}

	static void initializeStore() {
		synchronized (FuturesMarketDataStore.class) {
			try (Connection conn = DatabaseManager.getConnection();
				 Statement stmt = conn.createStatement()) {
				stmt.execute("PRAGMA busy_timeout=5000");
				stmt.execute(
					"CREATE TABLE IF NOT EXISTS FuturesLiveCapturedBars ("
						+ "symbol TEXT NOT NULL, timestamp TEXT NOT NULL, open REAL, high REAL, low REAL, close REAL, volume REAL, updatedAt TEXT, "
						+ "PRIMARY KEY(symbol, timestamp)"
						+ ")"
				);
				stmt.execute(
					"CREATE TABLE IF NOT EXISTS FuturesHistoricalLevel2Snapshots ("
						+ "symbol TEXT NOT NULL, timestamp TEXT NOT NULL, bestBid REAL, bestAsk REAL, spreadTicks REAL, "
						+ "depthImbalance3 REAL, depthImbalance5 REAL, depthImbalance10 REAL, topBookImbalance REAL, tapeDelta REAL, cvd REAL, "
						+ "bidWallDistanceTicks REAL, askWallDistanceTicks REAL, bidStacking REAL, askStacking REAL, absorption TEXT, "
						+ "liquidityVacuum INTEGER, bookFlip TEXT, flowState TEXT, source TEXT, sourceConfidence REAL, sourceDetail TEXT, updatedAt TEXT, "
						+ "PRIMARY KEY(symbol, timestamp)"
						+ ")"
				);
				stmt.execute(
					"CREATE TABLE IF NOT EXISTS FuturesMarketDataReconciliations ("
						+ "reconciliationID INTEGER PRIMARY KEY AUTOINCREMENT, symbols TEXT, startedAt TEXT, completedAt TEXT, status TEXT, summary TEXT"
						+ ")"
				);
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}

	static void recordRealtimeEvent(String hub, String eventType, String symbol, String payloadJson, String receivedAt) {
		if (!"market".equalsIgnoreCase(clean(hub)) || isBlank(symbol)) {
			return;
		}
		initializeStore();
		String normalizedType = clean(eventType);
		if (!"GatewayDepth".equalsIgnoreCase(normalizedType)) {
			recordLevel1Event(symbol, payloadJson, receivedAt);
		}
		recordLevel2Snapshot(symbol);
	}

	static String reconcileAfterLiveStop(String symbols) {
		initializeStore();
		String cleanSymbols = cleanSymbols(symbols);
		String startedAt = displayTime();
		String stopFlush = flushCapturedLevel1ToNativeHistory(cleanSymbols);
		String topstep = FuturesConnectionManager.importTopstepxBars(cleanSymbols, "2025-05-01", LocalDate.now(NEW_YORK_ZONE).toString(), 1);
		String level2 = fillLevel2Gaps(cleanSymbols);
		boolean success = !topstep.contains("\"success\":false") && !level2.contains("\"success\":false");
		String summary = "Level 1 live capture flush: " + jsonSummary(stopFlush)
			+ "; Topstep gap fill: " + jsonSummary(topstep)
			+ "; Level 2 gap fill: " + jsonSummary(level2);
		insertReconciliation(cleanSymbols, startedAt, displayTime(), success ? "COMPLETED" : "NEEDS_ATTENTION", summary);
		return "{"
			+ "\"success\":" + success + ","
			+ "\"symbols\":" + jsonString(cleanSymbols) + ","
			+ "\"level1Capture\":" + stopFlush + ","
			+ "\"topstepGapFill\":" + topstep + ","
			+ "\"level2GapFill\":" + level2 + ","
			+ "\"message\":" + jsonString(summary)
			+ "}";
	}

	static String flushCapturedLevel1ToNativeHistory(String symbols) {
		initializeStore();
		List<String> symbolList = parseSymbols(symbols);
		StringBuilder results = new StringBuilder("[");
		boolean success = true;
		for (int index = 0; index < symbolList.size(); index++) {
			String symbol = symbolList.get(index);
			if (index > 0) {
				results.append(",");
			}
			try {
				int captured = mergeCapturedBarsForSymbol(symbol);
				results.append("{\"symbol\":").append(jsonString(symbol)).append(",\"success\":true,\"capturedRows\":").append(captured).append("}");
			} catch (Exception e) {
				success = false;
				results.append("{\"symbol\":").append(jsonString(symbol)).append(",\"success\":false,\"message\":").append(jsonString(clean(e.getMessage()))).append("}");
			}
		}
		results.append("]");
		return "{\"success\":" + success + ",\"symbols\":" + results + "}";
	}

	static String fillLevel2Gaps(String symbols) {
		initializeStore();
		List<String> symbolList = parseSymbols(symbols);
		StringBuilder results = new StringBuilder("[");
		boolean success = true;
		for (int index = 0; index < symbolList.size(); index++) {
			if (index > 0) {
				results.append(",");
			}
			String symbol = symbolList.get(index);
			String result = fillLevel2GapsForSymbol(symbol);
			if (result.contains("\"success\":false")) {
				success = false;
			}
			results.append(result);
		}
		results.append("]");
		return "{\"success\":" + success + ",\"symbols\":" + results + "}";
	}

	static String fillLevel2GapsForSymbol(String symbol) {
		initializeStore();
		String normalized = normalizeSymbol(symbol);
		try {
			List<FuturesConnectionManager.InternalBar> bars = FuturesConnectionManager.readInternalFuturesBars(normalized);
			int beforeCaptured = countLevel2Rows(normalized, SOURCE_LIVE_CAPTURED);
			int beforeDerived = countLevel2Rows(normalized, SOURCE_DERIVED_GAP_FILL);
			int created = 0;
			for (FuturesConnectionManager.InternalBar bar : bars) {
				String timestamp = normalizedTimestamp(bar.timestampText);
				if (timestamp.length() == 0 || hasLevel2Row(normalized, timestamp)) {
					continue;
				}
				upsertDerivedLevel2(normalized, timestamp, bar);
				created++;
			}
			int finalRows = countLevel2Rows(normalized, null);
			return "{"
				+ "\"symbol\":" + jsonString(normalized) + ","
				+ "\"success\":true,"
				+ "\"level1Rows\":" + bars.size() + ","
				+ "\"capturedRows\":" + beforeCaptured + ","
				+ "\"derivedRows\":" + (beforeDerived + created) + ","
				+ "\"createdDerivedRows\":" + created + ","
				+ "\"finalRows\":" + finalRows
				+ "}";
		} catch (Exception e) {
			return "{\"symbol\":" + jsonString(normalized) + ",\"success\":false,\"message\":" + jsonString(clean(e.getMessage())) + "}";
		}
	}

	static String level2StatsBySymbolJson(List<String> symbols) {
		initializeStore();
		StringBuilder json = new StringBuilder("{");
		for (int index = 0; index < symbols.size(); index++) {
			String symbol = normalizeSymbol(symbols.get(index));
			if (index > 0) {
				json.append(",");
			}
			json.append(jsonString(symbol)).append(":").append(level2StatsJson(symbol));
		}
		json.append("}");
		return json.toString();
	}

	static String latestReconciliationSummaryJson() {
		initializeStore();
		String sql = "SELECT symbols, startedAt, completedAt, status, summary FROM FuturesMarketDataReconciliations ORDER BY reconciliationID DESC LIMIT 1";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql);
			 ResultSet rs = pstmt.executeQuery()) {
			if (rs.next()) {
				return "{"
					+ "\"symbols\":" + jsonString(rs.getString("symbols")) + ","
					+ "\"startedAt\":" + jsonString(rs.getString("startedAt")) + ","
					+ "\"completedAt\":" + jsonString(rs.getString("completedAt")) + ","
					+ "\"status\":" + jsonString(rs.getString("status")) + ","
					+ "\"summary\":" + jsonString(rs.getString("summary"))
					+ "}";
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return "null";
	}

	static void upsertLevel2SnapshotForTest(
		String symbol,
		String timestamp,
		double bestBid,
		double bestAsk,
		double spreadTicks,
		double imbalance,
		double tapeDelta,
		double cvd,
		String source
	) {
		initializeStore();
		upsertLevel2Snapshot(
			normalizeSymbol(symbol),
			normalizedTimestamp(timestamp),
			bestBid,
			bestAsk,
			spreadTicks,
			imbalance,
			imbalance,
			imbalance,
			imbalance,
			tapeDelta,
			cvd,
			0.0,
			0.0,
			0.0,
			0.0,
			"NONE",
			false,
			"",
			imbalance >= 0.25 ? "BID_HEAVY" : (imbalance <= -0.25 ? "ASK_HEAVY" : "BALANCED"),
			source,
			SOURCE_LIVE_CAPTURED.equals(source) ? 1.0 : 0.35,
			"test"
		);
	}

	private static void recordLevel1Event(String symbol, String payloadJson, String receivedAt) {
		double price = realtimePriceFromPayload(payloadJson);
		if (price <= 0.0) {
			return;
		}
		double volume = realtimeVolumeFromPayload(payloadJson);
		String timestamp = bucketTimestamp(receivedAt);
		if (timestamp.length() == 0) {
			return;
		}
		String sql = "INSERT INTO FuturesLiveCapturedBars (symbol, timestamp, open, high, low, close, volume, updatedAt) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
			+ "ON CONFLICT(symbol, timestamp) DO UPDATE SET "
			+ "high = MAX(high, excluded.high), low = MIN(low, excluded.low), close = excluded.close, volume = volume + excluded.volume, updatedAt = excluded.updatedAt";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeSymbol(symbol));
			pstmt.setString(2, timestamp);
			pstmt.setDouble(3, price);
			pstmt.setDouble(4, price);
			pstmt.setDouble(5, price);
			pstmt.setDouble(6, price);
			pstmt.setDouble(7, Math.max(0.0, volume));
			pstmt.setString(8, displayTime());
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static void recordLevel2Snapshot(String symbol) {
		LiveRuntimeState.OrderFlowSnapshot snapshot = LiveRuntimeState.orderFlowSnapshot(symbol);
		if (snapshot == null || !snapshot.available || snapshot.bestBid <= 0.0 || snapshot.bestAsk <= 0.0) {
			return;
		}
		String timestamp = bucketTimestamp(snapshot.lastUpdatedAt);
		if (timestamp.length() == 0) {
			return;
		}
		upsertLevel2Snapshot(
			normalizeSymbol(symbol),
			timestamp,
			snapshot.bestBid,
			snapshot.bestAsk,
			snapshot.spreadTicks,
			snapshot.depthImbalance3,
			snapshot.depthImbalance5,
			snapshot.depthImbalance10,
			snapshot.topBookImbalance,
			snapshot.tapeDelta,
			snapshot.cvd,
			snapshot.bidWallDistanceTicks,
			snapshot.askWallDistanceTicks,
			snapshot.bidStacking,
			snapshot.askStacking,
			cleanOrDefault(snapshot.absorption, "NONE"),
			snapshot.liquidityVacuum,
			cleanOrDefault(snapshot.bookFlip, ""),
			cleanOrDefault(snapshot.flowState, "BALANCED"),
			SOURCE_LIVE_CAPTURED,
			1.0,
			"ProjectX GatewayDepth/quote/trade live capture"
		);
	}

	private static int mergeCapturedBarsForSymbol(String symbol) throws Exception {
		String normalized = normalizeSymbol(symbol);
		List<FuturesConnectionManager.InternalBar> captured = readCapturedBars(normalized);
		if (captured.isEmpty()) {
			return 0;
		}
		Map<Instant, FuturesConnectionManager.InternalBar> merged = new TreeMap<Instant, FuturesConnectionManager.InternalBar>();
		for (FuturesConnectionManager.InternalBar bar : FuturesConnectionManager.readInternalFuturesBars(normalized)) {
			if (bar.timestamp != null) {
				merged.put(bar.timestamp, bar);
			}
		}
		for (FuturesConnectionManager.InternalBar bar : captured) {
			if (bar.timestamp != null) {
				merged.put(bar.timestamp, bar);
			}
		}
		FuturesConnectionManager.writeInternalFuturesBars(normalized, new ArrayList<FuturesConnectionManager.InternalBar>(merged.values()));
		return captured.size();
	}

	private static List<FuturesConnectionManager.InternalBar> readCapturedBars(String symbol) throws SQLException {
		List<FuturesConnectionManager.InternalBar> bars = new ArrayList<FuturesConnectionManager.InternalBar>();
		String sql = "SELECT timestamp, open, high, low, close, volume FROM FuturesLiveCapturedBars WHERE symbol = ? ORDER BY timestamp";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeSymbol(symbol));
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					FuturesConnectionManager.InternalBar bar = new FuturesConnectionManager.InternalBar();
					bar.timestampText = rs.getString("timestamp");
					bar.timestamp = parseInstant(bar.timestampText);
					bar.open = rs.getDouble("open");
					bar.high = rs.getDouble("high");
					bar.low = rs.getDouble("low");
					bar.close = rs.getDouble("close");
					bar.volume = rs.getDouble("volume");
					bar.vwap = (bar.high + bar.low + bar.close) / 3.0;
					bars.add(bar);
				}
			}
		}
		return bars;
	}

	private static String level2StatsJson(String symbol) {
		String normalized = normalizeSymbol(symbol);
		int captured = countLevel2Rows(normalized, SOURCE_LIVE_CAPTURED);
		int derived = countLevel2Rows(normalized, SOURCE_DERIVED_GAP_FILL);
		int total = captured + derived;
		int level1Rows = 0;
		try {
			level1Rows = FuturesConnectionManager.readInternalFuturesBars(normalized).size();
		} catch (Exception ignored) {
		}
		String[] range = level2Range(normalized);
		double coverage = level1Rows <= 0 ? 0.0 : Math.min(100.0, (total * 100.0) / level1Rows);
		return "{"
			+ "\"capturedRows\":" + captured + ","
			+ "\"derivedRows\":" + derived + ","
			+ "\"totalRows\":" + total + ","
			+ "\"level1Rows\":" + level1Rows + ","
			+ "\"coveragePct\":" + round(coverage) + ","
			+ "\"firstTimestamp\":" + jsonString(range[0]) + ","
			+ "\"lastTimestamp\":" + jsonString(range[1])
			+ "}";
	}

	private static void upsertDerivedLevel2(String symbol, String timestamp, FuturesConnectionManager.InternalBar bar) {
		double tick = tickSizeForSymbol(symbol);
		double safeTick = Math.max(0.000001, tick);
		double range = Math.max(safeTick, bar.high - bar.low);
		double closeLocation = clamp((bar.close - bar.low) / range, 0.0, 1.0);
		double direction = bar.close > bar.open ? 1.0 : (bar.close < bar.open ? -1.0 : 0.0);
		double bodyStrength = clamp(Math.abs(bar.close - bar.open) / range, 0.0, 1.0);
		double closeBias = (closeLocation - 0.5) * 2.0;
		double imbalance = clamp((closeBias * 0.45) + (direction * bodyStrength * 0.35), -0.80, 0.80);
		double spreadTicks = derivedSpreadTicks(bar, safeTick);
		double halfSpread = Math.max(safeTick, spreadTicks * safeTick) / 2.0;
		double bestBid = roundToTick(bar.close - halfSpread, safeTick);
		double bestAsk = roundToTick(bar.close + halfSpread, safeTick);
		if (bestAsk <= bestBid) {
			bestAsk = roundToTick(bestBid + safeTick, safeTick);
		}
		double signedVolume = direction == 0.0 ? closeBias * bar.volume * 0.20 : direction * bar.volume * (0.20 + (bodyStrength * 0.35));
		double wallDistance = Math.max(1.0, Math.min(12.0, Math.max(1.0, (bar.high - bar.low) / safeTick) / 6.0));
		upsertLevel2Snapshot(
			symbol,
			timestamp,
			bestBid,
			bestAsk,
			spreadTicks,
			imbalance,
			imbalance,
			imbalance,
			imbalance,
			signedVolume,
			signedVolume,
			imbalance >= 0.18 ? Math.max(1.0, wallDistance * 0.65) : wallDistance,
			imbalance <= -0.18 ? Math.max(1.0, wallDistance * 0.65) : wallDistance,
			Math.max(0.0, imbalance) * 10.0,
			Math.max(0.0, -imbalance) * 10.0,
			"NONE",
			spreadTicks >= 3.0 && Math.max(0.0, (bar.high - bar.low) / safeTick) >= 8.0,
			"",
			imbalance >= 0.25 ? "BID_HEAVY" : (imbalance <= -0.25 ? "ASK_HEAVY" : "BALANCED"),
			SOURCE_DERIVED_GAP_FILL,
			0.35,
			"Derived from reconciled Level 1 candle geometry because no captured Level 2 row existed"
		);
	}

	private static void upsertLevel2Snapshot(
		String symbol,
		String timestamp,
		double bestBid,
		double bestAsk,
		double spreadTicks,
		double depthImbalance3,
		double depthImbalance5,
		double depthImbalance10,
		double topBookImbalance,
		double tapeDelta,
		double cvd,
		double bidWallDistanceTicks,
		double askWallDistanceTicks,
		double bidStacking,
		double askStacking,
		String absorption,
		boolean liquidityVacuum,
		String bookFlip,
		String flowState,
		String source,
		double sourceConfidence,
		String sourceDetail
	) {
		if (isBlank(symbol) || isBlank(timestamp)) {
			return;
		}
		if (SOURCE_DERIVED_GAP_FILL.equals(source) && hasLiveCapturedLevel2Row(symbol, timestamp)) {
			return;
		}
		String sql = "INSERT INTO FuturesHistoricalLevel2Snapshots "
			+ "(symbol, timestamp, bestBid, bestAsk, spreadTicks, depthImbalance3, depthImbalance5, depthImbalance10, topBookImbalance, tapeDelta, cvd, "
			+ "bidWallDistanceTicks, askWallDistanceTicks, bidStacking, askStacking, absorption, liquidityVacuum, bookFlip, flowState, source, sourceConfidence, sourceDetail, updatedAt) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
			+ "ON CONFLICT(symbol, timestamp) DO UPDATE SET "
			+ "bestBid = excluded.bestBid, bestAsk = excluded.bestAsk, spreadTicks = excluded.spreadTicks, depthImbalance3 = excluded.depthImbalance3, "
			+ "depthImbalance5 = excluded.depthImbalance5, depthImbalance10 = excluded.depthImbalance10, topBookImbalance = excluded.topBookImbalance, "
			+ "tapeDelta = excluded.tapeDelta, cvd = excluded.cvd, bidWallDistanceTicks = excluded.bidWallDistanceTicks, askWallDistanceTicks = excluded.askWallDistanceTicks, "
			+ "bidStacking = excluded.bidStacking, askStacking = excluded.askStacking, absorption = excluded.absorption, liquidityVacuum = excluded.liquidityVacuum, "
			+ "bookFlip = excluded.bookFlip, flowState = excluded.flowState, source = excluded.source, sourceConfidence = excluded.sourceConfidence, "
			+ "sourceDetail = excluded.sourceDetail, updatedAt = excluded.updatedAt";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeSymbol(symbol));
			pstmt.setString(2, timestamp);
			pstmt.setDouble(3, bestBid);
			pstmt.setDouble(4, bestAsk);
			pstmt.setDouble(5, spreadTicks);
			pstmt.setDouble(6, depthImbalance3);
			pstmt.setDouble(7, depthImbalance5);
			pstmt.setDouble(8, depthImbalance10);
			pstmt.setDouble(9, topBookImbalance);
			pstmt.setDouble(10, tapeDelta);
			pstmt.setDouble(11, cvd);
			pstmt.setDouble(12, bidWallDistanceTicks);
			pstmt.setDouble(13, askWallDistanceTicks);
			pstmt.setDouble(14, bidStacking);
			pstmt.setDouble(15, askStacking);
			pstmt.setString(16, cleanOrDefault(absorption, "NONE"));
			pstmt.setInt(17, liquidityVacuum ? 1 : 0);
			pstmt.setString(18, cleanOrDefault(bookFlip, ""));
			pstmt.setString(19, cleanOrDefault(flowState, "BALANCED"));
			pstmt.setString(20, cleanOrDefault(source, SOURCE_DERIVED_GAP_FILL));
			pstmt.setDouble(21, sourceConfidence);
			pstmt.setString(22, cleanOrDefault(sourceDetail, ""));
			pstmt.setString(23, displayTime());
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static boolean hasLevel2Row(String symbol, String timestamp) {
		String sql = "SELECT 1 FROM FuturesHistoricalLevel2Snapshots WHERE symbol = ? AND timestamp = ? LIMIT 1";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeSymbol(symbol));
			pstmt.setString(2, timestamp);
			try (ResultSet rs = pstmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			return false;
		}
	}

	private static boolean hasLiveCapturedLevel2Row(String symbol, String timestamp) {
		String sql = "SELECT 1 FROM FuturesHistoricalLevel2Snapshots WHERE symbol = ? AND timestamp = ? AND source = ? LIMIT 1";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeSymbol(symbol));
			pstmt.setString(2, timestamp);
			pstmt.setString(3, SOURCE_LIVE_CAPTURED);
			try (ResultSet rs = pstmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			return false;
		}
	}

	private static int countLevel2Rows(String symbol, String source) {
		String sql = source == null
			? "SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = ?"
			: "SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = ? AND source = ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeSymbol(symbol));
			if (source != null) {
				pstmt.setString(2, source);
			}
			try (ResultSet rs = pstmt.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		}
	}

	private static String[] level2Range(String symbol) {
		String[] range = {"", ""};
		String sql = "SELECT MIN(timestamp) AS firstTimestamp, MAX(timestamp) AS lastTimestamp FROM FuturesHistoricalLevel2Snapshots WHERE symbol = ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizeSymbol(symbol));
			try (ResultSet rs = pstmt.executeQuery()) {
				if (rs.next()) {
					range[0] = clean(rs.getString("firstTimestamp"));
					range[1] = clean(rs.getString("lastTimestamp"));
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return range;
	}

	private static void insertReconciliation(String symbols, String startedAt, String completedAt, String status, String summary) {
		String sql = "INSERT INTO FuturesMarketDataReconciliations (symbols, startedAt, completedAt, status, summary) VALUES (?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, cleanSymbols(symbols));
			pstmt.setString(2, startedAt);
			pstmt.setString(3, completedAt);
			pstmt.setString(4, status);
			pstmt.setString(5, summary);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static String bucketTimestamp(String value) {
		Instant instant = parseInstant(value);
		if (instant == null) {
			return "";
		}
		long bucket = (instant.getEpochSecond() / 60L) * 60L;
		return Instant.ofEpochSecond(bucket).toString();
	}

	private static String normalizedTimestamp(String value) {
		Instant instant = parseInstant(value);
		if (instant == null) {
			return "";
		}
		return Instant.ofEpochSecond((instant.getEpochSecond() / 60L) * 60L).toString();
	}

	private static Instant parseInstant(String value) {
		String clean = clean(value).replace("\"", "");
		if (clean.length() == 0) {
			return null;
		}
		try {
			if (clean.endsWith("Z")) {
				return Instant.parse(clean);
			}
			if (clean.contains("+")) {
				return java.time.OffsetDateTime.parse(clean).toInstant();
			}
			if (clean.contains("T")) {
				return LocalDateTime.parse(clean).atZone(NEW_YORK_ZONE).toInstant();
			}
			if (clean.length() == 16) {
				return LocalDateTime.parse(clean, DISPLAY_MINUTE_FORMAT).atZone(NEW_YORK_ZONE).toInstant();
			}
			return LocalDateTime.parse(clean, DISPLAY_TIME_FORMAT).atZone(NEW_YORK_ZONE).toInstant();
		} catch (Exception ignored) {
			return null;
		}
	}

	private static double realtimePriceFromPayload(String payloadJson) {
		double direct = jsonFirstNumber(payloadJson, new String[] {"price", "Price", "lastPrice", "LastPrice", "tradePrice", "close", "c"}, 0.0);
		if (direct > 0.0) {
			return direct;
		}
		double bid = jsonFirstNumber(payloadJson, new String[] {"bid", "Bid", "bestBid", "BestBid", "bidPrice", "bp"}, 0.0);
		double ask = jsonFirstNumber(payloadJson, new String[] {"ask", "Ask", "bestAsk", "BestAsk", "askPrice", "ap"}, 0.0);
		if (bid > 0.0 && ask > 0.0) {
			return (bid + ask) / 2.0;
		}
		return Math.max(bid, ask);
	}

	private static double realtimeVolumeFromPayload(String payloadJson) {
		return jsonFirstNumber(payloadJson, new String[] {"volume", "Volume", "size", "Size", "lastSize", "LastSize", "v"}, 0.0);
	}

	private static double jsonFirstNumber(String json, String[] keys, double defaultValue) {
		if (json == null) {
			return defaultValue;
		}
		for (String key : keys) {
			double value = jsonNumber(json, key, Double.NaN);
			if (!Double.isNaN(value)) {
				return value;
			}
		}
		return defaultValue;
	}

	private static double jsonNumber(String json, String key, double defaultValue) {
		String pattern = "\"" + key + "\"";
		int keyIndex = json.indexOf(pattern);
		if (keyIndex < 0) {
			return defaultValue;
		}
		int colon = json.indexOf(':', keyIndex + pattern.length());
		if (colon < 0) {
			return defaultValue;
		}
		int start = colon + 1;
		while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
			start++;
		}
		int end = start;
		while (end < json.length()) {
			char ch = json.charAt(end);
			if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
				end++;
			} else {
				break;
			}
		}
		if (end <= start) {
			return defaultValue;
		}
		try {
			return Double.parseDouble(json.substring(start, end));
		} catch (Exception ignored) {
			return defaultValue;
		}
	}

	private static double derivedSpreadTicks(FuturesConnectionManager.InternalBar bar, double tickSize) {
		double rangeTicks = tickSize <= 0.0 ? 0.0 : (bar.high - bar.low) / tickSize;
		if (rangeTicks >= 80.0) {
			return 3.0;
		}
		if (rangeTicks >= 45.0) {
			return 2.0;
		}
		return 1.0;
	}

	private static double tickSizeForSymbol(String symbol) {
		String normalized = normalizeSymbol(symbol);
		if ("M2K".equals(normalized)) return 0.10;
		if ("MYM".equals(normalized)) return 1.0;
		if ("MGC".equals(normalized) || "GC".equals(normalized)) return 0.10;
		if ("MCL".equals(normalized)) return 0.01;
		return 0.25;
	}

	private static double roundToTick(double value, double tickSize) {
		double tick = Math.max(0.000001, tickSize);
		return Math.round(value / tick) * tick;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double round(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return 0.0;
		}
		return Math.round(value * 100.0) / 100.0;
	}

	private static List<String> parseSymbols(String symbols) {
		List<String> list = new ArrayList<String>();
		String[] parts = cleanSymbols(symbols).split(",");
		for (String part : parts) {
			String symbol = normalizeSymbol(part);
			if (symbol.length() > 0 && !list.contains(symbol)) {
				list.add(symbol);
			}
		}
		if (list.isEmpty()) {
			list.add("MES");
			list.add("MNQ");
			list.add("NQ");
			list.add("MGC");
			list.add("ES");
			list.add("M2K");
			list.add("MYM");
			list.add("MCL");
		}
		return list;
	}

	private static String cleanSymbols(String symbols) {
		return cleanOrDefault(symbols, "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL");
	}

	private static String normalizeSymbol(String symbol) {
		String clean = clean(symbol).toUpperCase(Locale.US);
		int dot = clean.indexOf('.');
		if (dot > 0) {
			clean = clean.substring(0, dot);
		}
		return clean.replaceAll("[^A-Z0-9]", "");
	}

	private static String clean(String value) {
		return value == null ? "" : value.trim();
	}

	private static String cleanOrDefault(String value, String defaultValue) {
		String clean = clean(value);
		return clean.length() == 0 ? defaultValue : clean;
	}

	private static boolean isBlank(String value) {
		return clean(value).length() == 0;
	}

	private static String displayTime() {
		return LocalDateTime.now(NEW_YORK_ZONE).format(DISPLAY_TIME_FORMAT);
	}

	private static String jsonString(String value) {
		String safe = value == null ? "" : value;
		return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
	}

	private static String jsonSummary(String json) {
		String clean = clean(json);
		return clean.length() > 220 ? clean.substring(0, 220) + "..." : clean;
	}
}
