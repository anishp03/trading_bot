package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

public class FuturesManagerBrokerReconcileTest {
	@Test
	public void brokerCloseFillReadsNumericTopstepOrderId() throws Exception {
		Object fill = brokerCloseFillFromTrade(
			"{\"accountId\":\"22539378\",\"symbol\":\"MES\",\"side\":\"BUY\",\"closed\":true,"
				+ "\"orderId\":3019677812,\"price\":7483.25,\"pnl\":158.8,\"fees\":3.7}",
			"2026-05-22T14:23:06.344808+00:00"
		);

		assertEquals("3019677812", stringField(fill, "orderId"));
	}

	@Test
	public void brokerCloseFallbackMatchesIsoCloseAfterLocalEntry() throws Exception {
		Object position = portfolioPosition("MES", "LONG", "22539378", "3019677800", "2026-05-22 10:20", 100.0, 98.0, 104.0);
		List<String> trades = Arrays.asList(
			"{\"accountId\":\"22539378\",\"symbol\":\"MES\",\"side\":\"SELL\",\"closed\":true,"
				+ "\"orderId\":3019680200,\"createdAt\":\"2026-05-22T14:23:06.344808+00:00\","
				+ "\"price\":104.0,\"pnl\":158.8,\"fees\":3.7}"
		);

		Object fill = matchingBrokerCloseFill(position, trades);

		assertEquals("3019680200", stringField(fill, "orderId"));
		assertEquals("ACCOUNT_SYMBOL_SIDE_AFTER_ENTRY", stringField(fill, "matchQuality"));
		assertTrue(booleanField(fill, "matched"));
		assertTrue(parseChartComparableTime("2026-05-22T14:23:06.344808+00:00") > 0L);
	}

	@Test
	public void flatAdapterResponseWithoutFillIsNotAuthoritative() throws Exception {
		assertFalse(brokerCloseHasAuthoritativeFill(
			"{\"success\":true,\"status\":\"FLAT_SYNC_TOPSTEPX\",\"message\":\"No open TopstepX position found\"}"
		));
		assertTrue(brokerCloseHasAuthoritativeFill(
			"{\"success\":true,\"status\":\"FLAT_SYNC_TOPSTEPX\",\"authoritative\":true,"
				+ "\"source\":\"TOPSTEPX_METRICS_RECONCILE\",\"exitPrice\":30420.0,"
				+ "\"fillTime\":\"2026-05-29T19:37:09.000000+00:00\",\"pnl\":138.6}"
		));
	}

	@Test
	public void brokerFlatSyncTradeUsesAuthoritativeTargetFill() throws Exception {
		Object position = portfolioPosition("NQ", "LONG", "22539378", "3050559018", "2026-05-29 15:36", 30413.0, 30406.75, 30420.25);
		Object fill = brokerCloseFillFromTrade(
			"{\"accountId\":\"22539378\",\"symbol\":\"NQ\",\"side\":\"SELL\",\"closed\":true,"
				+ "\"orderId\":3050559020,\"createdAt\":\"2026-05-29T19:37:09.000000+00:00\","
				+ "\"price\":30420.0,\"pnl\":138.6,\"fees\":1.4}",
			"2026-05-29T19:37:09.000000+00:00"
		);

		Object trade = liveFlatSyncTrade(position, fill);

		assertEquals(30420.0, doubleField(trade, "exitPrice"));
		assertEquals(138.6, doubleField(trade, "pnl"));
		assertEquals("Broker target fill; Topstep order 3050559020.", stringField(trade, "exitReason"));
	}

	private static Object brokerCloseFillFromTrade(String trade, String createdAt) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("brokerCloseFillFromTrade", String.class, String.class);
		method.setAccessible(true);
		return method.invoke(null, trade, createdAt);
	}

	private static Object matchingBrokerCloseFill(Object position, List<String> trades) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("matchingBrokerCloseFill", position.getClass(), List.class);
		method.setAccessible(true);
		return method.invoke(null, position, trades);
	}

	private static boolean brokerCloseHasAuthoritativeFill(String closeJson) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("brokerCloseHasAuthoritativeFill", String.class);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, closeJson)).booleanValue();
	}

	private static Object liveFlatSyncTrade(Object position, Object closeFill) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveFlatSyncTrade", position.getClass(), closeFill.getClass());
		method.setAccessible(true);
		return method.invoke(null, position, closeFill);
	}

	private static long parseChartComparableTime(String value) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("parseChartComparableTime", String.class);
		method.setAccessible(true);
		return ((Long) method.invoke(null, value)).longValue();
	}

	private static Object portfolioPosition(String symbol, String side, String accountId, String orderId, String openedAt, double entry, double stop, double target) throws Exception {
		Class<?> type = Class.forName("com.tradingbot.FuturesManager$PortfolioPosition");
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object position = constructor.newInstance();
		setField(position, "symbol", symbol);
		setField(position, "side", side);
		setField(position, "accountId", accountId);
		setField(position, "brokerOrderId", orderId);
		setField(position, "openedAt", openedAt);
		setField(position, "entryPrice", Double.valueOf(entry));
		setField(position, "stopPrice", Double.valueOf(stop));
		setField(position, "targetPrice", Double.valueOf(target));
		setField(position, "initialRisk", Double.valueOf(Math.abs(entry - stop)));
		return position;
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static String stringField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return (String) field.get(target);
	}

	private static boolean booleanField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return ((Boolean) field.get(target)).booleanValue();
	}

	private static double doubleField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return ((Double) field.get(target)).doubleValue();
	}
}
