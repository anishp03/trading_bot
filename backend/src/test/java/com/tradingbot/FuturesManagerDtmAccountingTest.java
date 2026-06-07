package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class FuturesManagerDtmAccountingTest {
	private static final int SESSION_ID = 990101;
	private static final int SNAPSHOT_ID = 77;

	@AfterEach
	public void cleanup() throws Exception {
		resetDynamicTradeStatesForSession(SESSION_ID);
	}

	@Test
	public void dynamicTradeStateAttachmentDoesNotDoubleCountRealizedPartialPnl() throws Exception {
		Object position = portfolioPosition("MNQ", "SHORT", "ORB2", "2026-06-05 09:37", "3053610000", 29912.25, 29918.25, 29800.00);
		setField(position, "dtmRealizedPnl", Double.valueOf(100.0));

		Object trade = futuresTrade();
		setField(trade, "pnl", Double.valueOf(250.0));

		Object state = dynamicTradeState();
		setField(state, "realizedPnl", Double.valueOf(100.0));
		setField(state, "timelineJson", "[{\"action\":\"DTM_PARTIAL_TARGET\"}]");
		setField(state, "finalAction", "DTM_RUNNER_FINAL_EXIT");
		setField(state, "partialDecision", "DTM_PARTIAL_TARGET");
		setField(state, "runnerDecision", "DTM_RUNNER_EXTENDED");
		liveDtmStates().put(dynamicTradeStateKey(SESSION_ID, SNAPSHOT_ID, position), state);

		applyDynamicTradeStateToTrade(SESSION_ID, SNAPSHOT_ID, position, trade, "");

		assertEquals(250.0, doubleField(trade, "pnl"), 0.0001);
		assertEquals("[{\"action\":\"DTM_PARTIAL_TARGET\"}]", stringField(trade, "dtmTimelineJson"));
		assertEquals("DTM_RUNNER_FINAL_EXIT", stringField(trade, "dtmFinalAction"));
		assertEquals("DTM_PARTIAL_TARGET", stringField(trade, "dtmPartialDecision"));
		assertEquals("DTM_RUNNER_EXTENDED", stringField(trade, "dtmRunnerDecision"));
	}

	private static Object portfolioPosition(String symbol, String side, String strategyCode, String openedAt, String orderId, double entry, double stop, double target) throws Exception {
		Object position = nestedInstance("PortfolioPosition");
		setField(position, "symbol", symbol);
		setField(position, "side", side);
		setField(position, "signal", signal(strategyCode, side));
		setField(position, "openedAt", openedAt);
		setField(position, "brokerOrderId", orderId);
		setField(position, "entryPrice", Double.valueOf(entry));
		setField(position, "stopPrice", Double.valueOf(stop));
		setField(position, "targetPrice", Double.valueOf(target));
		return position;
	}

	private static Object signal(String strategyCode, String side) throws Exception {
		Object signal = nestedInstance("Signal");
		setField(signal, "strategyCode", strategyCode);
		setField(signal, "strategyName", strategyCode);
		setField(signal, "side", side);
		return signal;
	}

	private static Object futuresTrade() throws Exception {
		return nestedInstance("FuturesTrade");
	}

	private static Object dynamicTradeState() throws Exception {
		return nestedInstance("DynamicTradeState");
	}

	private static Object nestedInstance(String simpleName) throws Exception {
		Class<?> type = Class.forName("com.tradingbot.FuturesManager$" + simpleName);
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> liveDtmStates() throws Exception {
		Field field = FuturesManager.class.getDeclaredField("LIVE_DTM_STATES");
		field.setAccessible(true);
		return (Map<String, Object>) field.get(null);
	}

	private static String dynamicTradeStateKey(int sessionId, int snapshotId, Object position) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("dynamicTradeStateKey", int.class, int.class, position.getClass());
		method.setAccessible(true);
		return (String) method.invoke(null, Integer.valueOf(sessionId), Integer.valueOf(snapshotId), position);
	}

	private static void applyDynamicTradeStateToTrade(int sessionId, int snapshotId, Object position, Object trade, String finalActionOverride) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"applyDynamicTradeStateToTrade",
			int.class,
			int.class,
			position.getClass(),
			trade.getClass(),
			String.class
		);
		method.setAccessible(true);
		method.invoke(null, Integer.valueOf(sessionId), Integer.valueOf(snapshotId), position, trade, finalActionOverride);
	}

	private static void resetDynamicTradeStatesForSession(int sessionId) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("resetDynamicTradeStatesForSession", int.class);
		method.setAccessible(true);
		method.invoke(null, Integer.valueOf(sessionId));
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

	private static double doubleField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return ((Double) field.get(target)).doubleValue();
	}
}
