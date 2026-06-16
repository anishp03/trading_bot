package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
	public void topstepAccountRefreshUsesBrokerNamesAndReplacesStaleDisplayId() throws Exception {
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
			"24740658",
			"",
			"",
			"",
			"",
			"",
			""
		);
		FuturesConnectionManager.saveTopstepAccount("50k Eval", "24740658", true);
		FuturesConnectionManager.saveTopstepAccount("150k Practice", "24154520", false);
		List<String> brokerAccounts = List.of(
			"{\"id\":24097033,\"name\":\"EXPRESS-V2-CT-DLL-592396-36395858\",\"balance\":-2007.62,\"canTrade\":true}",
			"{\"id\":24175826,\"name\":\"50KTC-V2-DLL-592396-24740658\",\"balance\":50000.0,\"canTrade\":true}",
			"{\"id\":24205194,\"name\":\"PRAC-V2-592396-62027599\",\"balance\":150000.0,\"canTrade\":true}"
		);

		String activeAccountId = FuturesConnectionManager.refreshSavedTopstepAccounts(brokerAccounts, "24740658");
		String accounts = FuturesConnectionManager.getTopstepAccountsJson();
		String connections = FuturesConnectionManager.getConnectionsJson();

		assertEquals("24175826", activeAccountId);
		assertTrue(accounts.contains("\"name\":\"50KTC-V2-DLL-592396-24740658\""), accounts);
		assertTrue(accounts.contains("\"name\":\"EXPRESS-V2-CT-DLL-592396-36395858\""), accounts);
		assertTrue(accounts.contains("\"name\":\"PRAC-V2-592396-62027599\""), accounts);
		assertTrue(accounts.contains("\"accountId\":\"24175826\""), accounts);
		assertFalse(accounts.contains("\"accountId\":\"24740658\""), accounts);
		assertFalse(accounts.contains("\"accountId\":\"24154520\""), accounts);
		assertTrue(connections.contains("\"accountId\":\"24175826\""), connections);
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
	public void topstepxTradeCostsSumEntireAccountHistory() {
		List<String> trades = List.of(
			"{\"profitAndLoss\":100.0,\"fees\":2.75,\"commission\":4.25}",
			"{\"profitAndLoss\":-25.0,\"fees\":1.10}",
			"{\"profitAndLoss\":12.0,\"commissions\":2.40}"
		);

		assertEquals(10.50, FuturesConnectionManager.topstepxTotalTradeCosts(trades), 0.0001);
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
	public void topstepxPartialCloseUsesRefreshedTokenAndConfirmsRunnerSize() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		try (FakeProjectXServer server = new FakeProjectXServer()) {
			saveTopstepConnection(server.baseUrl());
			server.on("/Auth/loginKey", exchange -> json(exchange, 200, "{\"success\":true,\"token\":\"stale-token\"}"));
			server.on("/Auth/validate", exchange -> json(exchange, 200, "{\"success\":true,\"newToken\":\"fresh-token\"}"));
			server.on("/Account/search", exchange -> requireFresh(exchange, "{\"success\":true,\"accounts\":[{\"id\":24175826,\"canTrade\":true}]}"));
			server.on("/Contract/search", exchange -> requireFresh(exchange, "{\"success\":true,\"contracts\":[{\"id\":\"CON.F.US.MNQ.M26\",\"name\":\"MNQM6\",\"symbolId\":\"F.US.MNQ\",\"activeContract\":true,\"tickSize\":0.25,\"tickValue\":0.5}]}"));
			AtomicInteger positionSearches = new AtomicInteger();
			server.on("/Position/searchOpen", exchange -> {
				requireFresh(exchange, positionSearches.incrementAndGet() == 1
					? "{\"success\":true,\"positions\":[{\"contractId\":\"CON.F.US.MNQ.M26\",\"size\":6,\"symbol\":\"MNQ\"}]}"
					: "{\"success\":true,\"positions\":[{\"contractId\":\"CON.F.US.MNQ.M26\",\"size\":3,\"symbol\":\"MNQ\"}]}");
			});
			server.on("/Position/partialCloseContract", exchange -> requireFresh(exchange, "{\"success\":true,\"orderId\":7001}"));

			String result = FuturesConnectionManager.partialCloseTopstepxPracticeSymbolPosition("24175826", "MNQ", 3);

			assertTrue(result.contains("\"success\":true"), result);
			assertTrue(result.contains("\"brokerVerified\":true"), result);
			assertTrue(result.contains("\"remainingSize\":3"), result);
			assertEquals("Bearer fresh-token", server.lastAuthorization("/Contract/search"));
			assertEquals(2, positionSearches.get());
		}
	}

	@Test
	public void topstepxSubmitFailsWhenBrokerStateDoesNotConfirmOrderOrPosition() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		try (FakeProjectXServer server = new FakeProjectXServer()) {
			saveTopstepConnection(server.baseUrl());
			server.on("/Auth/loginKey", exchange -> json(exchange, 200, "{\"success\":true,\"token\":\"fresh-token\"}"));
			server.on("/Auth/validate", exchange -> json(exchange, 200, "{\"success\":true,\"newToken\":\"fresh-token\"}"));
			server.on("/Account/search", exchange -> requireFresh(exchange, "{\"success\":true,\"accounts\":[{\"id\":24175826,\"canTrade\":true}]}"));
			server.on("/Contract/search", exchange -> requireFresh(exchange, "{\"success\":true,\"contracts\":[{\"id\":\"CON.F.US.MNQ.M26\",\"name\":\"MNQM6\",\"symbolId\":\"F.US.MNQ\",\"activeContract\":true,\"tickSize\":0.25,\"tickValue\":0.5}]}"));
			server.on("/Order/place", exchange -> requireFresh(exchange, "{\"success\":true,\"orderId\":8001}"));
			server.on("/Position/searchOpen", exchange -> requireFresh(exchange, "{\"success\":true,\"positions\":[]}"));
			server.on("/Order/searchOpen", exchange -> requireFresh(exchange, "{\"success\":true,\"orders\":[]}"));

			String result = FuturesConnectionManager.submitTopstepxPracticeOrder("24175826", "MNQ", "SHORT", 2, 30724.50, 30752.75, 30694.75, "live-MNQ-OMOM-test");

			assertTrue(result.contains("\"success\":false"), result);
			assertTrue(result.contains("\"brokerVerified\":false"), result);
			assertTrue(result.contains("not confirmed"), result);
		}
	}

	@Test
	public void topstepxProtectiveModifyRetriesTransientBrokerFailureAndVerifiesOrders() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		try (FakeProjectXServer server = new FakeProjectXServer()) {
			saveTopstepConnection(server.baseUrl());
			server.on("/Auth/loginKey", exchange -> json(exchange, 200, "{\"success\":true,\"token\":\"fresh-token\"}"));
			server.on("/Auth/validate", exchange -> json(exchange, 200, "{\"success\":true,\"newToken\":\"fresh-token\"}"));
			server.on("/Account/search", exchange -> requireFresh(exchange, "{\"success\":true,\"accounts\":[{\"id\":24175826,\"canTrade\":true}]}"));
			server.on("/Contract/search", exchange -> requireFresh(exchange, "{\"success\":true,\"contracts\":[{\"id\":\"CON.F.US.MNQ.M26\",\"name\":\"MNQM6\",\"symbolId\":\"F.US.MNQ\",\"activeContract\":true,\"tickSize\":0.25,\"tickValue\":0.5}]}"));
			AtomicInteger orderSearches = new AtomicInteger();
			server.on("/Order/searchOpen", exchange -> {
				int call = orderSearches.incrementAndGet();
				String orders = call == 1
					? "[{\"id\":9101,\"contractId\":\"CON.F.US.MNQ.M26\",\"type\":4,\"stopPrice\":30752.75,\"size\":6},{\"id\":9102,\"contractId\":\"CON.F.US.MNQ.M26\",\"type\":1,\"limitPrice\":30694.75,\"size\":6}]"
					: "[{\"id\":9101,\"contractId\":\"CON.F.US.MNQ.M26\",\"type\":4,\"stopPrice\":30720.25,\"size\":3},{\"id\":9102,\"contractId\":\"CON.F.US.MNQ.M26\",\"type\":1,\"limitPrice\":30640.25,\"size\":3}]";
				requireFresh(exchange, "{\"success\":true,\"orders\":" + orders + "}");
			});
			AtomicInteger modifyCalls = new AtomicInteger();
			server.on("/Order/modify", exchange -> {
				if (modifyCalls.incrementAndGet() == 1) {
					json(exchange, 429, "");
					return;
				}
				requireFresh(exchange, "{\"success\":true}");
			});

			String result = FuturesConnectionManager.modifyTopstepxPracticeProtectiveOrders("24175826", "MNQ", 30720.25, 30640.25, 3);

			assertTrue(result.contains("\"success\":true"), result);
			assertTrue(result.contains("\"brokerVerified\":true"), result);
			assertTrue(result.contains("\"verifiedOrders\":2"), result);
			assertTrue(modifyCalls.get() >= 3, result);
			assertEquals(2, orderSearches.get());
		}
	}

	@Test
	public void topstepxCloseRetriesTransientBrokerFailureAndCancelsProtection() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		try (FakeProjectXServer server = new FakeProjectXServer()) {
			saveTopstepConnection(server.baseUrl());
			server.on("/Auth/loginKey", exchange -> json(exchange, 200, "{\"success\":true,\"token\":\"fresh-token\"}"));
			server.on("/Auth/validate", exchange -> json(exchange, 200, "{\"success\":true,\"newToken\":\"fresh-token\"}"));
			server.on("/Account/search", exchange -> requireFresh(exchange, "{\"success\":true,\"accounts\":[{\"id\":24175826,\"canTrade\":true}]}"));
			server.on("/Contract/search", exchange -> requireFresh(exchange, "{\"success\":true,\"contracts\":[{\"id\":\"CON.F.US.MNQ.M26\",\"name\":\"MNQM6\",\"symbolId\":\"F.US.MNQ\",\"activeContract\":true,\"tickSize\":0.25,\"tickValue\":0.5}]}"));
			server.on("/Position/searchOpen", exchange -> requireFresh(exchange, "{\"success\":true,\"positions\":[{\"contractId\":\"CON.F.US.MNQ.M26\",\"size\":2,\"symbol\":\"MNQ\"}]}"));
			AtomicInteger closeCalls = new AtomicInteger();
			server.on("/Position/closeContract", exchange -> {
				if (closeCalls.incrementAndGet() == 1) {
					json(exchange, 429, "");
					return;
				}
				requireFresh(exchange, "{\"success\":true}");
			});
			server.on("/Order/searchOpen", exchange -> requireFresh(exchange, "{\"success\":true,\"orders\":[{\"id\":9201,\"contractId\":\"CON.F.US.MNQ.M26\",\"type\":4,\"stopPrice\":30752.75,\"size\":2},{\"id\":9202,\"contractId\":\"CON.F.US.MNQ.M26\",\"type\":1,\"limitPrice\":30694.75,\"size\":2}]}"));
			server.on("/Order/cancel", exchange -> requireFresh(exchange, "{\"success\":true}"));

			String result = FuturesConnectionManager.closeTopstepxPracticeSymbolPosition("24175826", "MNQ");

			assertTrue(result.contains("\"success\":true"), result);
			assertTrue(result.contains("\"positionsClosed\":1"), result);
			assertTrue(result.contains("\"ordersCanceled\":2"), result);
			assertEquals(2, closeCalls.get());
		}
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

	private static void saveTopstepConnection(String baseUrl) {
		FuturesConnectionManager.saveConnection(
			"TOPSTEPX",
			true,
			baseUrl,
			"PRACTICE_COMBINE",
			"test-user",
			"test-api-key",
			"",
			"",
			"",
			"",
			"",
			"24175826",
			"",
			"",
			"",
			"",
			"",
			""
		);
	}

	private static void requireFresh(HttpExchange exchange, String responseJson) throws Exception {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (!"Bearer fresh-token".equals(authorization)) {
			json(exchange, 401, "{\"success\":false,\"errorMessage\":\"stale token\"}");
			return;
		}
		json(exchange, 200, responseJson);
	}

	private static void json(HttpExchange exchange, int status, String responseJson) throws Exception {
		byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private interface ExchangeHandler {
		void handle(HttpExchange exchange) throws Exception;
	}

	private static class FakeProjectXServer implements AutoCloseable {
		private final HttpServer server;
		private final Map<String, ExchangeHandler> handlers = new HashMap<String, ExchangeHandler>();
		private final Map<String, List<String>> authorizationsByPath = new HashMap<String, List<String>>();

		private FakeProjectXServer() throws Exception {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", this::handle);
			server.start();
		}

		private String baseUrl() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		private void on(String path, ExchangeHandler handler) {
			handlers.put(path, handler);
		}

		private String lastAuthorization(String path) {
			List<String> values = authorizationsByPath.get(path);
			return values == null || values.isEmpty() ? "" : values.get(values.size() - 1);
		}

		private void handle(HttpExchange exchange) {
			try {
				String path = exchange.getRequestURI().getPath();
				authorizationsByPath.computeIfAbsent(path, ignored -> new ArrayList<String>()).add(clean(exchange.getRequestHeaders().getFirst("Authorization")));
				ExchangeHandler handler = handlers.get(path);
				if (handler == null) {
					json(exchange, 404, "{\"success\":false,\"message\":\"missing handler for " + path + "\"}");
					return;
				}
				handler.handle(exchange);
			} catch (Exception e) {
				try {
					json(exchange, 500, "{\"success\":false,\"message\":\"" + clean(e.getMessage()) + "\"}");
				} catch (Exception ignored) {
				}
			}
		}

		@Override
		public void close() {
			server.stop(0);
		}

		private static String clean(String value) {
			return value == null ? "" : value.replace("\"", "'");
		}
	}
}
