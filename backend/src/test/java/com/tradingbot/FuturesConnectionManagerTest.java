package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FuturesConnectionManagerTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void tearDown() {
		System.clearProperty("tradingbot.futuresDataDir");
		TestDatabaseSupport.clearTempDatabase();
	}

	@Test
	public void projectxBracketTicksAreSignedFromEntryPrice() {
		assertEquals(-156, FuturesConnectionManager.bracketTicksFromEntry(29614.0, 29575.0, 0.25));
		assertEquals(125, FuturesConnectionManager.bracketTicksFromEntry(29614.0, 29645.25, 0.25));
		assertEquals(34, FuturesConnectionManager.bracketTicksFromEntry(29664.25, 29672.75, 0.25));
		assertEquals(-41, FuturesConnectionManager.bracketTicksFromEntry(29664.25, 29654.0, 0.25));
	}

	@Test
	public void projectxRealtimePlanAllowsConfiguredEvalAccount() {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesConnectionManager.saveConnection(
			"TOPSTEPX",
			true,
			"https://api.topstepx.com/api",
			"DEMO",
			"test-user",
			"test-api-key",
			"",
			"",
			"",
			"",
			"",
			"22529998",
			"",
			"",
			"",
			"",
			"",
			""
		);

		String plan = ProjectXRealtimeManager.getPlanJson("MES,MNQ", true);

		assertTrue(plan.contains("\"accountId\":\"22529998\""), plan);
		assertTrue(plan.contains("\"accountOk\":true"), plan);
		assertFalse(plan.contains("22539378"), plan);
	}

	@Test
	public void futuresConnectionsDoNotExposeDatabentoProvider() {
		TestDatabaseSupport.useTempDatabase(tempDir);

		String connections = FuturesConnectionManager.getConnectionsJson();
		String requirements = FuturesConnectionManager.getRequirementsJson();

		assertFalse(connections.contains("DATABENTO"), connections);
		assertFalse(requirements.contains("DATABENTO"), requirements);
		assertTrue(connections.contains("TOPSTEPX"), connections);
	}

	@Test
	public void topstepxTradeCostAddsFeesAndCommission() {
		String payload = "{\"profitAndLoss\":100.0,\"fees\":2.75,\"commission\":4.25}";

		assertEquals(7.0, FuturesConnectionManager.topstepxTradeCost(payload), 0.0001);
	}

	@Test
	public void rebuildDerivedDataCreatesCandleConsistentSyntheticLevel2() throws Exception {
		Path futuresDir = tempDir.resolve("futures");
		Path oneMinuteDir = futuresDir.resolve("1min");
		Files.createDirectories(oneMinuteDir);
		System.setProperty("tradingbot.futuresDataDir", futuresDir.toString());
		Files.write(
			oneMinuteDir.resolve("MNQ.csv"),
			(
				"timestamp,open,high,low,close,volume\n"
					+ "2026-05-01T13:30:00Z,19000.00,19004.00,18999.00,19003.00,1000\n"
					+ "2026-05-01T13:31:00Z,19003.00,19005.00,19001.00,19001.50,800\n"
			).getBytes(StandardCharsets.UTF_8)
		);

		String json = FuturesConnectionManager.rebuildDerivedFuturesData("MNQ");
		Path syntheticLevel2 = futuresDir.resolve("level2-synthetic").resolve("MNQ.csv");
		String generated = new String(Files.readAllBytes(syntheticLevel2), StandardCharsets.UTF_8);

		assertTrue(json.contains("\"success\":true"), json);
		assertTrue(json.contains("syntheticLevel2Path"), json);
		assertTrue(generated.contains("timestamp,best_bid,best_ask,spread_ticks,depth_imbalance5,tape_delta"), generated);
		assertTrue(generated.contains("2026-05-01T13:30:00Z"), generated);
		assertTrue(generated.contains("19003.00000000,1000.00000000"), generated);
		assertEquals(3, generated.split("\\R").length);
	}
}
