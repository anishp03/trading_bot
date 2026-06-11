package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FuturesMarketDataStoreTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void tearDown() {
		System.clearProperty("tradingbot.futuresDataDir");
		TestDatabaseSupport.clearTempDatabase();
	}

	@Test
	public void level2GapFillPreservesCapturedRowsAndDerivesOnlyMissingMinutes() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		Path futuresDir = tempDir.resolve("futures");
		Path oneMinuteDir = futuresDir.resolve("1min");
		Files.createDirectories(oneMinuteDir);
		System.setProperty("tradingbot.futuresDataDir", futuresDir.toString());
		Files.write(
			oneMinuteDir.resolve("MNQ.csv"),
			(
				"timestamp,open,high,low,close,volume\n"
					+ "2026-06-03T13:30:00Z,19000.00,19004.00,18999.00,19003.00,1000\n"
					+ "2026-06-03T13:31:00Z,19003.00,19005.00,19001.00,19001.50,800\n"
			).getBytes(StandardCharsets.UTF_8)
		);
		FuturesConnectionManager.rebuildDerivedFuturesData("MNQ");

		FuturesMarketDataStore.upsertLevel2SnapshotForTest(
			"MNQ",
			"2026-06-03T13:30:00Z",
			19002.75,
			19003.00,
			1.0,
			0.42,
			14.0,
			25.0,
			"LIVE_CAPTURED"
		);

		String result = FuturesMarketDataStore.fillLevel2GapsForSymbol("MNQ");

		assertTrue(result.contains("\"capturedRows\":1"), result);
		assertTrue(result.contains("\"derivedRows\":1"), result);
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MNQ' AND source = 'LIVE_CAPTURED'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MNQ' AND source = 'DERIVED_GAP_FILL'"));
		assertEquals(2, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MNQ'"));
	}

	@Test
	public void level2GapFillHonorsRequestedDateRange() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		Path futuresDir = tempDir.resolve("futures");
		Path oneMinuteDir = futuresDir.resolve("1min");
		Files.createDirectories(oneMinuteDir);
		System.setProperty("tradingbot.futuresDataDir", futuresDir.toString());
		Files.write(
			oneMinuteDir.resolve("MGC.csv"),
			(
				"timestamp,open,high,low,close,volume\n"
					+ "2026-06-03T13:30:00Z,4500.00,4502.00,4498.00,4501.00,100\n"
					+ "2026-06-04T13:30:00Z,4520.00,4523.00,4519.00,4522.00,120\n"
			).getBytes(StandardCharsets.UTF_8)
		);

		String result = FuturesMarketDataStore.fillLevel2GapsForSymbol("MGC", "2026-06-04", "2026-06-04");

		assertTrue(result.contains("\"createdDerivedRows\":1"), result);
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MGC' AND source = 'DERIVED_GAP_FILL'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MGC' AND timestamp = '2026-06-04T13:30:00Z'"));
		assertEquals(0, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MGC' AND timestamp = '2026-06-03T13:30:00Z'"));
	}

	@Test
	public void level2GapFillDerivesOnlyRthMinutesInsideRequestedDateRange() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		Path futuresDir = tempDir.resolve("futures");
		Path oneMinuteDir = futuresDir.resolve("1min");
		Files.createDirectories(oneMinuteDir);
		System.setProperty("tradingbot.futuresDataDir", futuresDir.toString());
		Files.write(
			oneMinuteDir.resolve("MNQ.csv"),
			(
				"timestamp,open,high,low,close,volume\n"
					+ "2026-06-04T12:00:00Z,19000.00,19001.00,18999.00,19000.50,100\n"
					+ "2026-06-04T13:30:00Z,19002.00,19005.00,19001.00,19004.00,120\n"
					+ "2026-06-04T19:59:00Z,19004.00,19006.00,19003.00,19005.00,130\n"
					+ "2026-06-04T20:00:00Z,19005.00,19007.00,19004.00,19006.00,140\n"
			).getBytes(StandardCharsets.UTF_8)
		);

		String result = FuturesMarketDataStore.fillLevel2GapsForSymbol("MNQ", "2026-06-04", "2026-06-04");

		assertTrue(result.contains("\"createdDerivedRows\":2"), result);
		assertEquals(2, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MNQ' AND source = 'DERIVED_GAP_FILL'"));
		assertEquals(0, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MNQ' AND timestamp = '2026-06-04T12:00:00Z'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MNQ' AND timestamp = '2026-06-04T13:30:00Z'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MNQ' AND timestamp = '2026-06-04T19:59:00Z'"));
		assertEquals(0, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesHistoricalLevel2Snapshots WHERE symbol = 'MNQ' AND timestamp = '2026-06-04T20:00:00Z'"));
	}

	@Test
	public void marketDataStatusIncludesLevel2CoverageStats() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		Path futuresDir = tempDir.resolve("futures");
		Path oneMinuteDir = futuresDir.resolve("1min");
		Files.createDirectories(oneMinuteDir);
		System.setProperty("tradingbot.futuresDataDir", futuresDir.toString());
		Files.write(
			oneMinuteDir.resolve("MES.csv"),
			(
				"timestamp,open,high,low,close,volume\n"
					+ "2026-06-03T13:30:00Z,5300.00,5301.00,5299.00,5300.50,1000\n"
			).getBytes(StandardCharsets.UTF_8)
		);
		FuturesConnectionManager.rebuildDerivedFuturesData("MES");
		FuturesMarketDataStore.upsertLevel2SnapshotForTest(
			"MES",
			"2026-06-03T13:30:00Z",
			5300.25,
			5300.50,
			1.0,
			0.20,
			4.0,
			4.0,
			"LIVE_CAPTURED"
		);
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MES",
			"[{\"price\":5300.75,\"volume\":2.0}]",
			"2026-06-03 09:31:00"
		);

		String json = FuturesManager.getMarketDataStatusJson();

		assertTrue(json.contains("\"level2StatsBySymbol\""), json);
		assertTrue(json.contains("\"MES\":{\"capturedRows\":1"), json);
		assertTrue(json.contains("\"coveragePct\":100.0"), json);
		assertTrue(json.contains("\"liveCapturedRowsBySymbol\""), json);
		assertTrue(json.contains("\"MES\":{\"rows\":1"), json);
	}

	@Test
	public void stopLiveMergesCapturedMarketDataWithoutTopstepGapFill() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		Path futuresDir = tempDir.resolve("futures");
		Files.createDirectories(futuresDir.resolve("1min"));
		System.setProperty("tradingbot.futuresDataDir", futuresDir.toString());
		FuturesMarketDataStore.initializeStore();
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MGC",
			"[{\"price\":4527.4,\"volume\":2.0}]",
			"2026-06-04 09:59:00"
		);

		String result = FuturesManager.stopLive();

		assertTrue(result.contains("\"success\":true"), result);
		assertTrue(result.contains("\"marketDataReconciliation\""), result);
		assertTrue(result.contains("\"level1CaptureMerge\""), result);
		assertTrue(result.contains("\"level2GapFill\""), result);
		assertTrue(!result.contains("\"topstepGapFill\""), result);
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesMarketDataReconciliations"));
		assertTrue(Files.readString(futuresDir.resolve("1min").resolve("MGC.csv")).contains("4527.4"), result);
	}

	@Test
	public void liveCaptureIgnoresCumulativeQuoteVolumeAndSumsTradeSizes() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesMarketDataStore.initializeStore();

		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayQuote",
			"MGC",
			"{\"lastPrice\":4528.8,\"bestBid\":4528.7,\"bestAsk\":4529.0,\"volume\":179748.0}",
			"2026-06-04 09:59:00"
		);
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayQuote",
			"MGC",
			"{\"lastPrice\":4528.5,\"bestBid\":4528.5,\"bestAsk\":4528.7,\"volume\":179755.0}",
			"2026-06-04 09:59:01"
		);
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MGC",
			"[{\"price\":4528.6,\"volume\":1.0},{\"price\":4528.6,\"volume\":2.0}]",
			"2026-06-04 09:59:02"
		);

		assertEquals(3.0, queryDouble("SELECT volume FROM FuturesLiveCapturedBars WHERE symbol = 'MGC'"), 0.0001);
	}

	@Test
	public void liveCaptureStoresOnlyRthRowsForBacktestData() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesMarketDataStore.initializeStore();

		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MES",
			"[{\"price\":5300.0,\"volume\":1.0}]",
			"2026-06-04 08:00:00"
		);
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MES",
			"[{\"price\":5301.0,\"volume\":2.0}]",
			"2026-06-04 09:30:00"
		);
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MES",
			"[{\"price\":5302.0,\"volume\":3.0}]",
			"2026-06-04 16:00:00"
		);

		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveCapturedBars WHERE symbol = 'MES'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveCapturedBars WHERE symbol = 'MES' AND timestamp = '2026-06-04T13:30:00Z'"));
		assertEquals(2.0, queryDouble("SELECT volume FROM FuturesLiveCapturedBars WHERE symbol = 'MES'"), 0.0001);
	}

	@Test
	public void recentCapturedBarsReturnLatestRowsInAscendingOrder() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesMarketDataStore.initializeStore();
		insertCapturedBar("MES", "2026-06-04T13:30:00Z", 5300.0);
		insertCapturedBar("MES", "2026-06-04T13:31:00Z", 5301.0);
		insertCapturedBar("MES", "2026-06-04T13:32:00Z", 5302.0);

		java.util.List<FuturesConnectionManager.InternalBar> bars = FuturesMarketDataStore.readRecentCapturedBars("MES", 2);

		assertEquals(2, bars.size());
		assertEquals("2026-06-04T13:31:00Z", bars.get(0).timestampText);
		assertEquals("2026-06-04T13:32:00Z", bars.get(1).timestampText);
		assertEquals(5302.0, bars.get(1).close, 0.0001);
	}

	@Test
	public void liveStrategyRealtimeBarsPreferRecentCapturedRows() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesMarketDataStore.initializeStore();
		insertCapturedBar("MES", "2026-06-04T13:30:00Z", 5300.0);
		insertCapturedBar("MES", "2026-06-04T13:31:00Z", 5301.0);
		insertCapturedBar("MES", "2026-06-04T13:32:00Z", 5302.0);

		java.lang.reflect.Method method = FuturesManager.class.getDeclaredMethod("realtimeBarsForSymbol", String.class, String.class, int.class);
		method.setAccessible(true);
		java.util.List<?> bars = (java.util.List<?>) method.invoke(null, "MES", "1m", 2);

		assertEquals(2, bars.size());
		assertEquals("2026-06-04 09:31", barDisplayTime(bars.get(0)));
		assertEquals("2026-06-04 09:32", barDisplayTime(bars.get(1)));
	}

	@Test
	public void liveStrategyRealtimeBarsIgnorePreviousSessionCapturedRowsWhenRealtimeSessionIsFresh() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesMarketDataStore.initializeStore();
		ProjectXRealtimeManager.initializeStore();
		insertCapturedBar("MES", "2026-06-10T19:48:00Z", 7288.25);
		insertRealtimeEvent("MES", "2026-06-11 09:30:00", 7330.0);
		insertRealtimeEvent("MES", "2026-06-11 09:31:00", 7331.0);
		Object originalRuntime = realtimeRuntimeField().get(null);
		try {
			setRealtimeRuntime("2026-06-11 09:30:00");
			cacheEmptyWarmup("MES", "1m", 5);

			Method method = FuturesManager.class.getDeclaredMethod("realtimeBarsForSymbol", String.class, String.class, int.class);
			method.setAccessible(true);
			java.util.List<?> bars = (java.util.List<?>) method.invoke(null, "MES", "1m", 5);

			assertEquals(2, bars.size());
			assertEquals("2026-06-11 09:30", barDisplayTime(bars.get(0)));
			assertEquals("2026-06-11 09:31", barDisplayTime(bars.get(1)));
		} finally {
			realtimeRuntimeField().set(null, originalRuntime);
			clearWarmupState();
		}
	}

	@Test
	public void liveMonitorCandlesPreferRecentCapturedRows() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesMarketDataStore.initializeStore();
		insertCapturedBar("MES", "2026-06-04T13:30:00Z", 5300.0);
		insertCapturedBar("MES", "2026-06-04T13:31:00Z", 5301.0);
		insertCapturedBar("MES", "2026-06-04T13:32:00Z", 5302.0);

		String json = FuturesManager.getLiveMonitorJson("MES", 2, "1m");

		assertTrue(json.contains("\"dataSource\":\"LIVE_CAPTURED_BARS\""), json);
		assertTrue(json.contains("\"capturedBars\":3"), json);
		assertTrue(json.contains("\"time\":\"2026-06-04 09:30\""), json);
		assertTrue(json.contains("\"time\":\"2026-06-04 09:31\""), json);
		assertTrue(json.contains("\"time\":\"2026-06-04 09:32\""), json);
		assertTrue(json.contains("\"eventType\":\"LIVE_CAPTURED_BARS\""), json);
	}

	@Test
	public void capturedMergePromotesOnlyRthRowsToBacktestCsv() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		Path futuresDir = tempDir.resolve("futures");
		Files.createDirectories(futuresDir.resolve("1min"));
		System.setProperty("tradingbot.futuresDataDir", futuresDir.toString());
		FuturesMarketDataStore.initializeStore();
		insertCapturedBar("MES", "2026-06-04T12:00:00Z", 5299.0);
		insertCapturedBar("MES", "2026-06-04T13:30:00Z", 5301.0);
		insertCapturedBar("MES", "2026-06-04T20:00:00Z", 5302.0);

		String result = FuturesMarketDataStore.flushCapturedLevel1ToNativeHistory("MES");

		String csv = Files.readString(futuresDir.resolve("1min").resolve("MES.csv"));
		assertTrue(result.contains("\"capturedRows\":1"), result);
		assertTrue(csv.contains("2026-06-04T13:30:00Z"), csv);
		assertTrue(!csv.contains("2026-06-04T12:00:00Z"), csv);
		assertTrue(!csv.contains("2026-06-04T20:00:00Z"), csv);
	}

	@Test
	public void tradeAnalysisFallsBackToLiveCapturedBarsWhenNativeCsvIsMissingTradeWindow() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		Path futuresDir = tempDir.resolve("futures");
		Files.createDirectories(futuresDir.resolve("1min"));
		System.setProperty("tradingbot.futuresDataDir", futuresDir.toString());
		FuturesMarketDataStore.initializeStore();
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MGC",
			"[{\"price\":4528.8,\"volume\":1.0}]",
			"2026-06-04 09:58:00"
		);
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MGC",
			"[{\"price\":4527.4,\"volume\":2.0}]",
			"2026-06-04 09:59:00"
		);
		FuturesMarketDataStore.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"MGC",
			"[{\"price\":4524.6,\"volume\":3.0}]",
			"2026-06-04 10:06:00"
		);

		String json = FuturesManager.getTradeAnalysisJson(
			"MGC",
			"ORB2",
			"Opening Range Retest",
			"SHORT",
			"2026-06-04T13:59:16+00:00",
			"2026-06-04T14:06:20+00:00",
			"4527.4",
			"4527.0",
			"4531.4",
			"4521.1"
		);

		assertTrue(json.contains("\"dataSource\":\"live captured bars fallback\""), json);
		assertTrue(json.contains("\"candles\":[{"), json);
		assertTrue(json.contains("\"time\":\"2026-06-04 09:59\""), json);
	}

	private double queryDouble(String sql) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
				 Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery(sql)) {
			return rs.next() ? rs.getDouble(1) : 0.0;
		}
	}

	private void insertCapturedBar(String symbol, String timestamp, double price) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.executeUpdate(
				"INSERT INTO FuturesLiveCapturedBars (symbol, timestamp, open, high, low, close, volume, updatedAt) VALUES ('"
					+ symbol + "', '" + timestamp + "', " + price + ", " + price + ", " + price + ", " + price + ", 1.0, 'test')"
			);
		}
	}

	private void insertRealtimeEvent(String symbol, String receivedAt, double price) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesLiveRealtimeEvents ("
					+ "realtimeEventID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "hub TEXT, eventType TEXT, accountId TEXT, contractId TEXT, symbol TEXT, payloadJson TEXT, receivedAt TEXT"
					+ ")"
			);
			stmt.executeUpdate(
				"INSERT INTO FuturesLiveRealtimeEvents (hub, eventType, accountId, contractId, symbol, payloadJson, receivedAt) VALUES ("
					+ "'market', 'GatewayTrade', '', '', '" + symbol + "', '[{\"price\":" + price + ",\"volume\":1.0}]', '" + receivedAt + "')"
			);
		}
	}

	private void setRealtimeRuntime(String startedAt) throws Exception {
		Class<?> runtimeClass = Class.forName("com.tradingbot.ProjectXRealtimeManager$RealtimeRuntime");
		Constructor<?> constructor = runtimeClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object runtime = constructor.newInstance();
		setField(runtime, "running", Boolean.TRUE);
		setField(runtime, "startedAt", startedAt);
		setField(runtime, "dataMode", "PROJECTX_SIGNALR");
		realtimeRuntimeField().set(null, runtime);
	}

	private void cacheEmptyWarmup(String symbol, String timeframe, int limit) throws Exception {
		Method keyMethod = FuturesManager.class.getDeclaredMethod("liveWarmupCacheKey", String.class, String.class, int.class);
		keyMethod.setAccessible(true);
		String cacheKey = (String) keyMethod.invoke(null, symbol, timeframe, limit);
		Class<?> warmupClass = Class.forName("com.tradingbot.FuturesManager$LiveWarmupBars");
		Constructor<?> constructor = warmupClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object warmup = constructor.newInstance();
		setField(warmup, "bars", new ArrayList<>());
		setField(warmup, "dataSource", "TEST_EMPTY_WARMUP");
		setField(warmup, "loadedAt", Long.valueOf(System.currentTimeMillis()));
		Field cacheField = FuturesManager.class.getDeclaredField("LIVE_WARMUP_CACHE");
		cacheField.setAccessible(true);
		((Map<String, Object>) cacheField.get(null)).put(cacheKey, warmup);
	}

	private void clearWarmupState() throws Exception {
		Field cacheField = FuturesManager.class.getDeclaredField("LIVE_WARMUP_CACHE");
		cacheField.setAccessible(true);
		((Map<?, ?>) cacheField.get(null)).clear();
	}

	private Field realtimeRuntimeField() throws Exception {
		Field field = ProjectXRealtimeManager.class.getDeclaredField("runtime");
		field.setAccessible(true);
		return field;
	}

	private void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		if (value instanceof Boolean) {
			field.setBoolean(target, ((Boolean) value).booleanValue());
		} else if (value instanceof Long) {
			field.setLong(target, ((Long) value).longValue());
		} else {
			field.set(target, value);
		}
	}

	private String barDisplayTime(Object bar) throws Exception {
		java.lang.reflect.Field field = bar.getClass().getDeclaredField("displayTime");
		field.setAccessible(true);
		return (String) field.get(bar);
	}
}
