package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FuturesLiveTradeCacheTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void clearRuntimeProperties() {
		System.clearProperty("tradingbot.liveTradeCacheDir");
	}

	@Test
	public void saveLiveTradeCacheDropsLocalDecisionRows() throws Exception {
		System.setProperty("tradingbot.liveTradeCacheDir", tempDir.toString());
		String payload = "{"
			+ "\"success\":true,"
			+ "\"version\":1,"
			+ "\"accountId\":\"24407573\","
			+ "\"updatedAt\":\"2026-06-19T15:28:50.682Z\","
			+ "\"rows\":["
			+ "{\"accountId\":\"24407573\",\"cacheSource\":\"local-decision\",\"symbol\":\"MGC\",\"strategyCode\":\"PDB\",\"status\":\"SOLD_TOPSTEP\",\"entryTime\":\"2026-06-18 15:01\"},"
			+ "{\"accountId\":\"24407573\",\"cacheSource\":\"topstep-enriched\",\"symbol\":\"MGC\",\"strategyCode\":\"OMOM\",\"status\":\"SOLD_TOPSTEP\",\"entryTime\":\"2026-06-18 10:29\"}"
			+ "]"
			+ "}";

		String result = FuturesManager.saveLiveTradeCacheJson("24407573", payload);
		String saved = Files.readString(tempDir.resolve("24407573.json"));

		assertTrue(result.contains("\"success\":true"), result);
		assertFalse(saved.contains("\"cacheSource\":\"local-decision\""), saved);
		assertTrue(saved.contains("\"cacheSource\":\"topstep-enriched\""), saved);
		assertTrue(saved.contains("\"droppedLocalDecisionRows\":1"), saved);
	}
}
