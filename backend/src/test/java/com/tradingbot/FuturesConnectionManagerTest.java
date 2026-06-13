package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
	public void topstepAccountsCanBeSavedListedAndActivated() {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesConnectionManager.saveConnection(
			"TOPSTEPX",
			true,
			"https://api.topstepx.com/api",
			"PRACTICE_COMBINE",
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

		String first = FuturesConnectionManager.saveTopstepAccount("Express Funded", "36395858", true);
		String second = FuturesConnectionManager.saveTopstepAccount("Practice 150K", "20450074", false);
		String activated = FuturesConnectionManager.activateTopstepAccount("20450074");
		String accounts = FuturesConnectionManager.getTopstepAccountsJson();
		String connections = FuturesConnectionManager.getConnectionsJson();

		assertTrue(first.contains("\"success\":true"), first);
		assertTrue(second.contains("\"success\":true"), second);
		assertTrue(activated.contains("\"accountId\":\"20450074\""), activated);
		assertTrue(accounts.contains("\"name\":\"Express Funded\""), accounts);
		assertTrue(accounts.contains("\"accountId\":\"36395858\""), accounts);
		assertTrue(accounts.contains("\"name\":\"Practice 150K\""), accounts);
		assertTrue(accounts.contains("\"active\":true"), accounts);
		assertTrue(connections.contains("\"accountId\":\"20450074\""), connections);
		assertTrue(connections.contains("\"topstepAccounts\""), connections);
	}

	@Test
	public void deletingActiveTopstepAccountClearsConnectedAccountId() {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesConnectionManager.saveConnection(
			"TOPSTEPX",
			true,
			"https://api.topstepx.com/api",
			"PRACTICE_COMBINE",
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
		FuturesConnectionManager.saveTopstepAccount("Connected Account", "22529998", true);
		FuturesConnectionManager.saveTopstepAccount("Express Funded", "36395858", false);

		String deleted = FuturesConnectionManager.deleteTopstepAccount("22529998");
		String accounts = FuturesConnectionManager.getTopstepAccountsJson();
		String connections = FuturesConnectionManager.getConnectionsJson();

		assertTrue(deleted.contains("\"success\":true"), deleted);
		assertFalse(accounts.contains("\"accountId\":\"22529998\""), accounts);
		assertTrue(accounts.contains("\"accountId\":\"36395858\""), accounts);
		assertTrue(connections.contains("\"accountId\":\"\""), connections);
		assertFalse(connections.contains("\"active\":true"), connections);
	}

	@Test
	public void topstepxTradeCostAddsFeesAndCommission() {
		String payload = "{\"profitAndLoss\":100.0,\"fees\":2.75,\"commission\":4.25}";

		assertEquals(7.0, FuturesConnectionManager.topstepxTradeCost(payload), 0.0001);
	}

	@Test
	public void topstepxFundedAccountBalancesTrackPnlInsteadOfEquity() {
		assertTrue(FuturesConnectionManager.topstepxAccountBalanceTracksPnl("EXPRESS-V2-CT-DLL-592396-36395858"));
		assertTrue(FuturesConnectionManager.topstepxAccountBalanceTracksPnl("50K Funded"));
		assertFalse(FuturesConnectionManager.topstepxAccountBalanceTracksPnl("PRAC-V2-592396-20450074"));
		assertFalse(FuturesConnectionManager.topstepxAccountBalanceTracksPnl("50KTC-V2-592396-32261585"));

		assertEquals(-1001.30, FuturesConnectionManager.topstepxAccountPnlFromBalance("EXPRESS-V2-CT-DLL-592396-36395858", -1001.30, 50000.0), 0.0001);
		assertEquals(27.50, FuturesConnectionManager.topstepxAccountPnlFromBalance("50K Funded", 27.50, 50000.0), 0.0001);
		assertEquals(33.90, FuturesConnectionManager.topstepxAccountPnlFromBalance("PRAC-V2-592396-20450074", 150033.90, 150000.0), 0.0001);
		assertEquals(3027.76, FuturesConnectionManager.topstepxAccountPnlFromBalance("50KTC-V2-592396-32261585", 53027.76, 50000.0), 0.0001);

		assertEquals(48998.70, FuturesConnectionManager.topstepxRiskBalanceFromProjectxBalance("EXPRESS-V2-CT-DLL-592396-36395858", -1001.30, 50000.0), 0.0001);
		assertEquals(150033.90, FuturesConnectionManager.topstepxRiskBalanceFromProjectxBalance("PRAC-V2-592396-20450074", 150033.90, 150000.0), 0.0001);
		assertEquals(150000.0, FuturesConnectionManager.topstepxAccountSizeFromBalance("PRAC-V2-592396-75358821", 149443.08, Double.NaN, 50000.0), 0.0001);
		assertEquals(50000.0, FuturesConnectionManager.topstepxAccountSizeFromBalance("50KTC-V2-592396-32261585", 53027.76, Double.NaN, 150000.0), 0.0001);
		assertEquals(50000.0, FuturesConnectionManager.topstepxAccountSizeFromBalance("EXPRESS-V2-CT-DLL-592396-36395858", -1001.30, Double.NaN, 50000.0), 0.0001);
	}

	@Test
	public void topstepxPracticeReplacementPrefersCurrentTradablePracticeAccount() {
		List<String> accounts = List.of(
			"{\"id\":24097033,\"name\":\"EXPRESS-V2-CT-DLL-592396-36395858\",\"balance\":-2007.62,\"canTrade\":false}",
			"{\"id\":24154520,\"name\":\"PRAC-V2-592396-79311688\",\"balance\":150607.76,\"canTrade\":true}",
			"{\"id\":24175826,\"name\":\"50KTC-V2-DLL-592396-24740658\",\"balance\":50000.0,\"canTrade\":true}"
		);

		assertEquals("24154520", FuturesConnectionManager.topstepxReplacementAccountId(accounts, "150k Practice"));
		assertEquals("", FuturesConnectionManager.topstepxReplacementAccountId(accounts, "50k Funded"));
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
