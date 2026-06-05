package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

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
}
