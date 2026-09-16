package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SystemRoutesTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void clearRuntimeProperties() {
		System.clearProperty("tradingbot.runtimeRoot");
	}

	@Test
	public void healthJsonReportsResolvedStoragePaths() {
		System.setProperty("tradingbot.runtimeRoot", tempDir.toString());

		String json = SystemRoutes.healthJson("test-version", 1L);

		assertTrue(json.contains("\"storage\""), json);
		assertTrue(json.contains("\"databasePath\":\"" + jsonEscape(tempDir.resolve("db/tradingbot.db").toString()) + "\""), json);
		assertTrue(json.contains("\"equityMarketDataDir\":\"" + jsonEscape(tempDir.resolve("market_data").toString()) + "\""), json);
		assertTrue(json.contains("\"futuresDataDir\":\"" + jsonEscape(tempDir.resolve("market_data/futures").toString()) + "\""), json);
		assertTrue(json.contains("\"liveTradeCacheDir\":\"" + jsonEscape(tempDir.resolve("data/live_trade_cache").toString()) + "\""), json);
	}

	private static String jsonEscape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
