package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FuturesLiveMonitorWarmupTest {
	private Object originalLiveSession;

	@BeforeEach
	public void setUp() throws Exception {
		originalLiveSession = liveSessionField().get(null);
		clearWarmupState();
	}

	@AfterEach
	public void tearDown() throws Exception {
		liveSessionField().set(null, originalLiveSession);
		clearWarmupState();
	}

	@Test
	public void monitorWarmupUsesLiveOnlyFallbackWhenHistoryCacheIsMissing() throws Exception {
		Object activeSession = newLiveSession();
		setBoolean(activeSession, "running", true);
		liveSessionField().set(null, activeSession);

		Object warmup = liveWarmupBarsForMonitorSymbol("MES", "1m", 40);

		assertEquals("PROJECTX_SIGNALR_LIVE_ONLY", stringField(warmup, "dataSource"));
		assertEquals(0, barsSize(warmup));
	}

	private static Object liveWarmupBarsForMonitorSymbol(String symbol, String timeframe, int limit) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveWarmupBarsForMonitorSymbol", String.class, String.class, int.class);
		method.setAccessible(true);
		return method.invoke(null, symbol, timeframe, limit);
	}

	private static Object newLiveSession() throws Exception {
		Class<?> sessionClass = Class.forName("com.tradingbot.FuturesManager$FuturesLiveSession");
		Constructor<?> constructor = sessionClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static Field liveSessionField() throws Exception {
		Field field = FuturesManager.class.getDeclaredField("liveSession");
		field.setAccessible(true);
		return field;
	}

	private static void clearWarmupState() throws Exception {
		Field cacheField = FuturesManager.class.getDeclaredField("LIVE_WARMUP_CACHE");
		cacheField.setAccessible(true);
		((Map<?, ?>) cacheField.get(null)).clear();
		Field loadingField = FuturesManager.class.getDeclaredField("LIVE_GRAPH_WARMUP_LOADING");
		loadingField.setAccessible(true);
		((Set<?>) loadingField.get(null)).clear();
	}

	private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.setBoolean(target, value);
	}

	private static String stringField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return (String) field.get(target);
	}

	private static int barsSize(Object warmup) throws Exception {
		Field field = warmup.getClass().getDeclaredField("bars");
		field.setAccessible(true);
		return ((java.util.List<?>) field.get(warmup)).size();
	}
}
