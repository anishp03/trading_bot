package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LiveRuntimeStateTest {
	@BeforeEach
	public void setUp() {
		LiveRuntimeState.clearForTest();
	}

	@AfterEach
	public void tearDown() {
		LiveRuntimeState.clearForTest();
	}

	@Test
	public void gatewayDepthArrayBuildsOrderFlowBookFromDisplayedVolume() {
		LiveRuntimeState.recordRealtimeEvent(
			"market",
			"GatewayDepth",
			"",
			"CON.F.US.MES.M26",
			"MES",
			"["
				+ "{\"price\":100.0,\"volume\":10.0,\"currentVolume\":0.0,\"type\":2.0},"
				+ "{\"price\":99.75,\"volume\":5.0,\"currentVolume\":0.0,\"type\":2.0},"
				+ "{\"price\":100.25,\"volume\":4.0,\"currentVolume\":0.0,\"type\":1.0},"
				+ "{\"price\":100.5,\"volume\":6.0,\"currentVolume\":0.0,\"type\":1.0}"
				+ "]",
			"2026-05-25 12:41:06"
		);

		String json = LiveRuntimeState.getOrderFlowJson("MES");

		assertTrue(json.contains("\"available\":true"), json);
		assertTrue(json.contains("\"bestBid\":100.0"), json);
		assertTrue(json.contains("\"bestAsk\":100.25"), json);
		assertTrue(json.contains("\"spreadTicks\":1.0"), json);
		assertTrue(json.contains("\"topBookImbalance\":0.43"), json);
		assertTrue(json.contains("\"depthImbalance5\":0.2"), json);
	}

	@Test
	public void crossedPartialQuoteDoesNotCreateUsableOrderFlowSnapshot() {
		LiveRuntimeState.recordRealtimeEvent(
			"market",
			"GatewayQuote",
			"",
			"CON.F.US.MES.M26",
			"MES",
			"{\"bestAsk\":100.25}",
			"2026-05-25 12:42:00"
		);
		LiveRuntimeState.recordRealtimeEvent(
			"market",
			"GatewayQuote",
			"",
			"CON.F.US.MES.M26",
			"MES",
			"{\"bestBid\":100.50}",
			"2026-05-25 12:42:01"
		);

		String json = LiveRuntimeState.getOrderFlowJson("MES");

		assertTrue(json.contains("\"available\":false"), json);
		assertTrue(json.contains("\"fresh\":false"), json);
		assertTrue(json.contains("\"flowState\":\"CROSSED_BOOK\""), json);
	}

	@Test
	public void crossedPartialQuoteFallsBackToSaneDepthTopOfBook() {
		LiveRuntimeState.recordRealtimeEvent(
			"market",
			"GatewayDepth",
			"",
			"CON.F.US.MES.M26",
			"MES",
			"["
				+ "{\"price\":100.0,\"volume\":10.0,\"type\":2.0},"
				+ "{\"price\":100.25,\"volume\":8.0,\"type\":1.0}"
				+ "]",
			"2026-05-25 12:42:00"
		);
		LiveRuntimeState.recordRealtimeEvent(
			"market",
			"GatewayQuote",
			"",
			"CON.F.US.MES.M26",
			"MES",
			"{\"bestAsk\":100.25}",
			"2026-05-25 12:42:01"
		);
		LiveRuntimeState.recordRealtimeEvent(
			"market",
			"GatewayQuote",
			"",
			"CON.F.US.MES.M26",
			"MES",
			"{\"bestBid\":100.50}",
			"2026-05-25 12:42:02"
		);

		String json = LiveRuntimeState.getOrderFlowJson("MES");

		assertTrue(json.contains("\"available\":true"), json);
		assertTrue(json.contains("\"fresh\":true"), json);
		assertTrue(json.contains("\"bestBid\":100.0"), json);
		assertTrue(json.contains("\"bestAsk\":100.25"), json);
		assertTrue(json.contains("\"spreadTicks\":1.0"), json);
	}

	@Test
	public void marksJsonIncludesMarketBrokerAndCheckState() {
		LiveRuntimeState.recordRealtimeEvent(
			"market",
			"GatewayTrade",
			"",
			"CON.F.US.MNQ.H26",
			"MNQ",
			"{\"price\":18025.25,\"volume\":3}",
			"2026-05-14 10:15"
		);
		LiveRuntimeState.updateBrokerMetricsJson(
			"{\"success\":true,\"accountSize\":150000,\"currentBalance\":149950.5,\"currentPnl\":-49.5,"
				+ "\"realizedPnl\":-60,\"unrealizedPnl\":10.5,\"drawdown\":49.5,\"returnPct\":-0.03,"
				+ "\"numberOfTrades\":2,\"openTrades\":1,\"syncedAt\":\"2026-05-14T14:15:00Z\","
				+ "\"positions\":[{\"symbol\":\"MNQ\",\"side\":\"LONG\",\"contracts\":1,\"entryPrice\":18020.0}],"
				+ "\"trades\":[{\"symbol\":\"MNQ\",\"side\":\"BUY\",\"contracts\":1,\"price\":18020.0}]}"
		);

		String json = LiveRuntimeState.getLiveMarksJson("MNQ", "1m");

		assertTrue(json.contains("\"success\":true"), json);
		assertTrue(json.contains("\"MNQ\""), json);
		assertTrue(json.contains("\"lastPrice\":18025.25"), json);
		assertTrue(json.contains("\"currentCandle\""), json);
		assertTrue(json.contains("\"brokerDataAuthoritative\":false"), json);
		assertTrue(json.contains("\"dataSource\":\"LIVE_MARKS\""), json);
		assertTrue(json.contains("\"currentBalance\":null"), json);
		assertTrue(json.contains("\"checks\""), json);
		assertTrue(json.contains("\"brokerAccount\""), json);
		assertTrue(json.contains("\"positions\":[]"), json);
		assertTrue(json.contains("\"brokerPositionCount\":1"), json);
	}

	@Test
	public void brokerMetricsCacheUsesRiskEquityWhenFundedBalanceTracksPnl() throws Exception {
		LiveRuntimeState.updateBrokerMetricsJson(
			"{\"success\":true,\"accountId\":\"24097033\",\"accountSize\":50000,"
				+ "\"currentBalance\":-1001.3,\"currentPnl\":-1001.3,"
				+ "\"riskCurrentBalance\":48998.7,\"equityBalance\":48998.7,"
				+ "\"balanceMode\":\"PNL\",\"balanceTracksPnl\":true,"
				+ "\"syncedAt\":\"2026-06-10T14:15:00Z\",\"positions\":[],\"orders\":[],\"trades\":[]}"
		);

		assertEquals(48998.7, privateStaticDouble("currentBalance"), 0.001);
	}

	@Test
	public void marksJsonWarnsAndFiltersWhenRequestedAccountDoesNotMatchBrokerCache() {
		LiveRuntimeState.updateBrokerMetricsJson(
			"{\"success\":true,\"accountId\":\"22539378\",\"accountSize\":150000,\"currentBalance\":149950.5,\"currentPnl\":-49.5,"
				+ "\"syncedAt\":\"2026-05-14T14:15:00Z\","
				+ "\"positions\":[{\"accountId\":\"22539378\",\"symbol\":\"MNQ\",\"side\":\"LONG\",\"contracts\":1,\"entryPrice\":18020.0}],"
				+ "\"trades\":[{\"accountId\":\"22539378\",\"symbol\":\"MNQ\",\"side\":\"BUY\",\"contracts\":1,\"price\":18020.0}]}"
		);

		String json = LiveRuntimeState.getLiveMarksJson("MNQ", "1m", "22529998");

		assertTrue(json.contains("\"brokerAccountMatched\":false"), json);
		assertTrue(json.contains("\"severity\":\"warn\""), json);
		assertTrue(json.contains("Broker cache is for account 22539378, not selected account 22529998."), json);
		assertTrue(json.contains("\"positions\":[]"), json);
		assertTrue(json.contains("\"trades\":[]"), json);
	}

	@Test
	public void authoritativeEmptyBrokerPositionsClearCachedRuntimePosition() {
		LiveRuntimeState.updateBrokerMetricsJson(
			"{\"success\":true,\"source\":\"TOPSTEPX\",\"accountId\":\"22539378\",\"openTrades\":1,"
				+ "\"positions\":[{\"accountId\":\"22539378\",\"symbol\":\"MGC\",\"side\":\"LONG\",\"contracts\":8,\"entryPrice\":4559.6}],"
				+ "\"orders\":[{\"accountId\":\"22539378\",\"symbol\":\"MGC\",\"status\":\"WORKING\"}],"
				+ "\"trades\":[]}"
		);
		assertTrue(LiveRuntimeState.getLiveMarksJson("MGC", "1m", "22539378").contains("\"brokerPositionCount\":1"));

		LiveRuntimeState.updateBrokerMetricsJson(
			"{\"success\":true,\"source\":\"TOPSTEPX\",\"accountId\":\"22539378\",\"openTrades\":0,"
				+ "\"positions\":[],\"orders\":[],\"trades\":[]}"
		);

		String json = LiveRuntimeState.getLiveMarksJson("MGC", "1m", "22539378");
		assertTrue(json.contains("\"positions\":[]"), json);
		assertTrue(json.contains("\"orders\":[]"), json);
		assertTrue(json.contains("\"brokerPositionCount\":0"), json);
		assertTrue(json.contains("\"brokerOrderCount\":0"), json);
	}

	@Test
	public void numericProjectxTerminalOrderStatusesClearRuntimeOrders() {
		LiveRuntimeState.recordRealtimeEvent(
			"user",
			"GatewayUserOrder",
			"22539378",
			"CON.F.US.MGC.M26",
			"MGC",
			"{\"id\":2984814387,\"accountId\":\"22539378\",\"contractId\":\"CON.F.US.MGC.M26\",\"symbol\":\"MGC\",\"status\":1,\"side\":1,\"size\":8}",
			"2026-05-15 10:29"
		);
		assertTrue(LiveRuntimeState.getLiveMarksJson("MGC", "1m", "22539378").contains("\"brokerOrderCount\":1"));

		LiveRuntimeState.recordRealtimeEvent(
			"user",
			"GatewayUserOrder",
			"22539378",
			"CON.F.US.MGC.M26",
			"MGC",
			"{\"id\":2984814387,\"accountId\":\"22539378\",\"contractId\":\"CON.F.US.MGC.M26\",\"symbol\":\"MGC\",\"status\":2.0,\"side\":1,\"size\":8}",
			"2026-05-15 10:30"
		);

		String json = LiveRuntimeState.getLiveMarksJson("MGC", "1m", "22539378");
		assertTrue(json.contains("\"orders\":[]"), json);
		assertTrue(json.contains("\"brokerOrderCount\":0"), json);
	}

	private static double privateStaticDouble(String fieldName) throws Exception {
		Field field = LiveRuntimeState.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.getDouble(null);
	}
}
