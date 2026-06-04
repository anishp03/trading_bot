package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

		String json = FuturesManager.getMarketDataStatusJson();

		assertTrue(json.contains("\"level2StatsBySymbol\""), json);
		assertTrue(json.contains("\"MES\":{\"capturedRows\":1"), json);
		assertTrue(json.contains("\"coveragePct\":100.0"), json);
	}

	@Test
	public void stopLiveDoesNotTriggerBacktestMarketDataReconciliation() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesMarketDataStore.initializeStore();

		String result = FuturesManager.stopLive();

		assertTrue(result.contains("\"success\":true"), result);
		assertEquals(0, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesMarketDataReconciliations"));
	}
}
