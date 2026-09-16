package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FuturesLiveMonitorWarmupTest {
	private Object originalLiveSession;
	private Object originalRealtimeRuntime;
	private long originalMarketGenerationCounter;
	private boolean originalShutdownRequested;
	private boolean originalReconnectScheduled;
	private int originalReconnectAttempt;
	private Map<String, Long> originalLastPersistedMarketEvents;

	@BeforeEach
	public void setUp() throws Exception {
		originalLiveSession = liveSessionField().get(null);
		originalRealtimeRuntime = realtimeRuntimeField().get(null);
		originalMarketGenerationCounter = marketGenerationCounterField().getLong(null);
		originalShutdownRequested = staticField("shutdownRequested").getBoolean(null);
		originalReconnectScheduled = staticField("reconnectScheduled").getBoolean(null);
		originalReconnectAttempt = staticField("reconnectAttempt").getInt(null);
		synchronized (ProjectXRealtimeManager.class) {
			originalLastPersistedMarketEvents = new LinkedHashMap<String, Long>(lastPersistedMarketEvents());
			lastPersistedMarketEvents().clear();
		}
		clearWarmupState();
	}

	@AfterEach
	public void tearDown() throws Exception {
		liveSessionField().set(null, originalLiveSession);
		realtimeRuntimeField().set(null, originalRealtimeRuntime);
		marketGenerationCounterField().setLong(null, originalMarketGenerationCounter);
		staticField("shutdownRequested").setBoolean(null, originalShutdownRequested);
		staticField("reconnectScheduled").setBoolean(null, originalReconnectScheduled);
		staticField("reconnectAttempt").setInt(null, originalReconnectAttempt);
		synchronized (ProjectXRealtimeManager.class) {
			lastPersistedMarketEvents().clear();
			lastPersistedMarketEvents().putAll(originalLastPersistedMarketEvents);
		}
		TestDatabaseSupport.clearTempDatabase();
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

	@Test
	public void graphWarmupUsesFullTimelineLimitForEveryTimeframe() throws Exception {
		assertEquals(2000, liveGraphWarmupLimit("1m"));
		assertEquals(2000, liveGraphWarmupLimit("5m"));
		assertEquals(2000, liveGraphWarmupLimit("30m"));
		assertEquals(2000, liveGraphWarmupLimit("1h"));
		assertEquals(2000, liveGraphWarmupLimit("4h"));
	}

	@Test
	public void liveMonitorSupportsFourHourTimeframeContract() throws Exception {
		assertEquals("4h", normalizeLiveMonitorTimeframe("4h"));
		assertEquals("4h", normalizeLiveMonitorTimeframe("240min"));
		assertEquals(240, liveMonitorTimeframeMinutes("4h"));
		assertEquals("4 hour", liveMonitorTimeframeLabel("4h"));
		assertEquals(LocalDateTime.of(2026, 6, 11, 8, 0), realtimeCandleBucket(LocalDateTime.of(2026, 6, 11, 11, 59), "4h"));
		assertEquals(LocalDateTime.of(2026, 6, 11, 12, 0), realtimeCandleBucket(LocalDateTime.of(2026, 6, 11, 12, 0), "4h"));
	}

	@Test
	public void topstepOrderEvaluationWaitsForFirstFreshMarketEvent() {
		assertTrue(FuturesManager.topstepxFeedReadyForOrderEvaluation(true, false));
		assertFalse(FuturesManager.topstepxFeedReadyForOrderEvaluation(false, true));
		assertFalse(FuturesManager.topstepxFeedReadyForOrderEvaluation(false, false));
		assertFalse(FuturesManager.topstepxFeedReadyForOrderEvaluation(true, true));
	}

	@Test
	public void projectxFeedReadinessIsScopedToTheCurrentMarketGeneration() throws Exception {
		Object realtimeRuntime = newRealtimeRuntime();
		setBoolean(realtimeRuntime, "running", true);
		realtimeRuntimeField().set(null, realtimeRuntime);

		long firstGeneration = beginMarketGeneration(realtimeRuntime);
		setBoolean(realtimeRuntime, "marketHubConnected", true);
		setBoolean(realtimeRuntime, "userHubConnected", true);
		setString(realtimeRuntime, "lastEventAt", "2026-09-16 01:00:00");

		Object firstWaitingFeed = currentMarketFeedFreshness();
		assertFalse(booleanField(firstWaitingFeed, "fresh"));
		assertTrue(booleanField(firstWaitingFeed, "warming"));
		assertEquals(firstGeneration, longField(firstWaitingFeed, "marketGeneration"));

		assertTrue(markMarketEventReceived(firstGeneration, "2026-09-16 01:00:01", System.currentTimeMillis()));
		Object firstReadyFeed = currentMarketFeedFreshness();
		assertTrue(booleanField(firstReadyFeed, "fresh"));
		assertTrue(LiveRuntimeState.getLiveMarksJson("MES", "1m").contains("\"feedFresh\":true"));

		long secondGeneration = beginMarketGeneration(realtimeRuntime);
		setBoolean(realtimeRuntime, "marketHubConnected", true);
		assertFalse(booleanField(currentMarketFeedFreshness(), "fresh"));
		assertTrue(LiveRuntimeState.getLiveMarksJson("MES", "1m").contains("\"feedFresh\":false"));
		assertFalse(markMarketEventReceived(firstGeneration, "2026-09-16 01:00:02", System.currentTimeMillis()));
		assertFalse(booleanField(currentMarketFeedFreshness(), "fresh"));
		assertTrue(markMarketEventReceived(secondGeneration, "2026-09-16 01:00:03", System.currentTimeMillis()));
		assertTrue(booleanField(currentMarketFeedFreshness(), "fresh"));

		assertTrue(disconnectMarketGeneration(secondGeneration));
		ProjectXRealtimeManager.MarketFeedState disconnected = ProjectXRealtimeManager.currentMarketFeedState();
		assertFalse(disconnected.marketHubConnected);
		assertEquals(0L, disconnected.lastMarketEventReceivedAtMillis);
		assertFalse(booleanField(currentMarketFeedFreshness(), "fresh"));
		assertFalse(markMarketEventReceived(secondGeneration, "2026-09-16 01:00:04", System.currentTimeMillis()));

		long reconnectGeneration = disconnected.marketGeneration;
		setBoolean(realtimeRuntime, "marketHubConnected", true);
		assertTrue(markMarketEventReceived(reconnectGeneration, "2026-09-16 01:00:05", System.currentTimeMillis()));
		assertTrue(booleanField(currentMarketFeedFreshness(), "fresh"));
	}

	@Test
	public void staleReconnectAndStartOwnersCannotMutateReplacementRuntime() throws Exception {
		Object runtimeA = newRealtimeRuntime();
		setBoolean(runtimeA, "running", true);
		setBoolean(runtimeA, "starting", true);
		realtimeRuntimeField().set(null, runtimeA);
		staticField("shutdownRequested").setBoolean(null, false);
		long generationA = beginMarketGeneration(runtimeA);

		assertTrue(reconnectRuntimeEligible(runtimeA));
		assertTrue(startRuntimeOwned(runtimeA, generationA));

		Object runtimeB = newRealtimeRuntime();
		setBoolean(runtimeB, "running", true);
		realtimeRuntimeField().set(null, runtimeB);

		assertFalse(reconnectRuntimeEligible(runtimeA));
		assertFalse(startRuntimeOwned(runtimeA, generationA));
		assertTrue(reconnectRuntimeEligible(runtimeB));

		realtimeRuntimeField().set(null, runtimeA);
		staticField("shutdownRequested").setBoolean(null, true);
		assertFalse(reconnectRuntimeEligible(runtimeA));
		assertFalse(startRuntimeOwned(runtimeA, generationA));
	}

	@Test
	public void callbackSymbolAndThrottleStayScopedToAcceptedGeneration() throws Exception {
		Object runtimeA = newRealtimeRuntime();
		setBoolean(runtimeA, "running", true);
		contractSymbols(runtimeA).put("CONTRACT-1", "MES");
		realtimeRuntimeField().set(null, runtimeA);
		staticField("shutdownRequested").setBoolean(null, false);
		long generationA = beginMarketGeneration(runtimeA);

		CountDownLatch symbolCaptured = new CountDownLatch(1);
		CountDownLatch runtimeReplaced = new CountDownLatch(1);
		AtomicReference<String> capturedSymbol = new AtomicReference<String>();
		AtomicBoolean oldGenerationPersisted = new AtomicBoolean(false);
		AtomicReference<Throwable> callbackFailure = new AtomicReference<Throwable>();
		Thread oldCallback = new Thread(() -> {
			try {
				capturedSymbol.set(resolveRealtimeSymbolForCallback(runtimeA, generationA, "market", "CONTRACT-1", "", "{}"));
				symbolCaptured.countDown();
				if (!runtimeReplaced.await(5L, TimeUnit.SECONDS)) {
					throw new AssertionError("Replacement runtime was not installed in time.");
				}
				oldGenerationPersisted.set(shouldPersistRealtimeEvent(generationA, "market", "GatewayQuote", capturedSymbol.get()));
			} catch (Throwable error) {
				callbackFailure.set(error);
			}
		}, "old-projectx-callback-test");
		oldCallback.setDaemon(true);
		oldCallback.start();

		assertTrue(symbolCaptured.await(5L, TimeUnit.SECONDS));
		Object runtimeB = newRealtimeRuntime();
		setBoolean(runtimeB, "running", true);
		contractSymbols(runtimeB).put("CONTRACT-1", "MNQ");
		realtimeRuntimeField().set(null, runtimeB);
		long generationB = beginMarketGeneration(runtimeB);
		runtimeReplaced.countDown();
		oldCallback.join(5000L);

		assertFalse(oldCallback.isAlive());
		if (callbackFailure.get() != null) {
			throw new AssertionError("Old callback race test failed.", callbackFailure.get());
		}
		assertEquals("MES", capturedSymbol.get());
		assertTrue(oldGenerationPersisted.get());
		assertTrue(shouldPersistRealtimeEvent(generationB, "market", "GatewayQuote", "MES"));
		assertTrue(resolveRealtimeSymbolForCallback(runtimeA, generationA, "market", "CONTRACT-1", "", "{}") == null);
		assertEquals("MNQ", resolveRealtimeSymbolForCallback(runtimeB, generationB, "market", "CONTRACT-1", "", "{}"));
	}

	@Test
	public void userHubCloseBeforeInitialAdoptionCannotReportConnectedOrAcceptQueuedEvents() throws Exception {
		Object startingRuntime = newRealtimeRuntime();
		setBoolean(startingRuntime, "starting", true);
		realtimeRuntimeField().set(null, startingRuntime);
		staticField("shutdownRequested").setBoolean(null, false);
		long generation = beginMarketGeneration(startingRuntime);

		assertFalse(markUserHubClosed(startingRuntime, generation, "closed during initial start"));
		assertTrue(booleanField(startingRuntime, "userHubClosedInGeneration"));
		assertFalse(userHubConnectedForAdoption(startingRuntime, generation, true));
		assertTrue(resolveRealtimeSymbolForCallback(startingRuntime, generation, "user", "", "MES", "{}") == null);
	}

	@Test
	public void userHubCloseBeforeReconnectAdoptionForcesAnotherReconnect() throws Exception {
		Object reconnectingRuntime = newRealtimeRuntime();
		setBoolean(reconnectingRuntime, "running", true);
		realtimeRuntimeField().set(null, reconnectingRuntime);
		staticField("shutdownRequested").setBoolean(null, false);
		long generation = beginMarketGeneration(reconnectingRuntime);

		assertTrue(markUserHubClosed(reconnectingRuntime, generation, "closed during reconnect"));
		assertTrue(booleanField(reconnectingRuntime, "userHubClosedInGeneration"));
		assertFalse(userHubConnectedForAdoption(reconnectingRuntime, generation, true));
	}

	@Test
	public void simulatedModeBypassesProjectxFeedGateAndTopstepSubmitRequiresSameGeneration() {
		assertTrue(FuturesManager.executionModeFeedReadyForOrderEvaluation("SIMULATED", false, false));
		assertFalse(FuturesManager.executionModeFeedReadyForOrderEvaluation("TOPSTEPX", false, true));
		assertTrue(FuturesManager.topstepxFeedGenerationReadyForSubmission(7L, 7L, true));
		assertFalse(FuturesManager.topstepxFeedGenerationReadyForSubmission(7L, 8L, true));
		assertFalse(FuturesManager.topstepxFeedGenerationReadyForSubmission(7L, 7L, false));
	}

	@Test
	public void feedGenerationDeferralDoesNotCreateSignalDecisionDedupeMarker(@TempDir Path tempDir) throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesManager.initializeStore();

		FuturesManager.recordTopstepxFeedGenerationDeferral(
			42,
			"MNQ",
			"2026-09-16 10:01:00",
			"ORB_RETEST",
			"LONG",
			7L,
			8L,
			"Feed generation changed before submit."
		);

		assertEquals(0, TestDatabaseSupport.countRows(
			"SELECT COUNT(*) FROM FuturesLiveSignalDecisions WHERE sessionID = 42 AND symbol = 'MNQ' AND strategyCode = 'ORB_RETEST'"
		));
		assertEquals(1, TestDatabaseSupport.countRows(
			"SELECT COUNT(*) FROM FuturesLiveAuditLog WHERE eventType = 'LIVE_SIGNAL_RETRY_FEED_GENERATION'"
		));
	}

	private static Object liveWarmupBarsForMonitorSymbol(String symbol, String timeframe, int limit) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveWarmupBarsForMonitorSymbol", String.class, String.class, int.class);
		method.setAccessible(true);
		return method.invoke(null, symbol, timeframe, limit);
	}

	private static int liveGraphWarmupLimit(String timeframe) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveGraphWarmupLimit", String.class);
		method.setAccessible(true);
		return ((Integer) method.invoke(null, timeframe)).intValue();
	}

	private static String normalizeLiveMonitorTimeframe(String timeframe) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("normalizeLiveMonitorTimeframe", String.class);
		method.setAccessible(true);
		return (String) method.invoke(null, timeframe);
	}

	private static int liveMonitorTimeframeMinutes(String timeframe) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveMonitorTimeframeMinutes", String.class);
		method.setAccessible(true);
		return ((Integer) method.invoke(null, timeframe)).intValue();
	}

	private static String liveMonitorTimeframeLabel(String timeframe) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveMonitorTimeframeLabel", String.class);
		method.setAccessible(true);
		return (String) method.invoke(null, timeframe);
	}

	private static LocalDateTime realtimeCandleBucket(LocalDateTime eventTime, String timeframe) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("realtimeCandleBucket", LocalDateTime.class, String.class);
		method.setAccessible(true);
		return (LocalDateTime) method.invoke(null, eventTime, timeframe);
	}

	private static Object newLiveSession() throws Exception {
		Class<?> sessionClass = Class.forName("com.tradingbot.FuturesManager$FuturesLiveSession");
		Constructor<?> constructor = sessionClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static Object newRealtimeRuntime() throws Exception {
		Class<?> runtimeClass = Class.forName("com.tradingbot.ProjectXRealtimeManager$RealtimeRuntime");
		Constructor<?> constructor = runtimeClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static long beginMarketGeneration(Object realtimeRuntime) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod("beginMarketGenerationLocked", realtimeRuntime.getClass());
		method.setAccessible(true);
		synchronized (ProjectXRealtimeManager.class) {
			return ((Long) method.invoke(null, realtimeRuntime)).longValue();
		}
	}

	private static boolean markMarketEventReceived(long generation, String receivedAt, long receivedAtMillis) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod("markMarketEventReceivedLocked", long.class, String.class, long.class);
		method.setAccessible(true);
		synchronized (ProjectXRealtimeManager.class) {
			return ((Boolean) method.invoke(null, Long.valueOf(generation), receivedAt, Long.valueOf(receivedAtMillis))).booleanValue();
		}
	}

	private static boolean disconnectMarketGeneration(long generation) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod("disconnectMarketGenerationLocked", long.class);
		method.setAccessible(true);
		synchronized (ProjectXRealtimeManager.class) {
			return ((Boolean) method.invoke(null, Long.valueOf(generation))).booleanValue();
		}
	}

	private static Object currentMarketFeedFreshness() throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("currentMarketFeedFreshness");
		method.setAccessible(true);
		return method.invoke(null);
	}

	private static boolean reconnectRuntimeEligible(Object realtimeRuntime) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod("reconnectRuntimeEligibleLocked", realtimeRuntime.getClass());
		method.setAccessible(true);
		synchronized (ProjectXRealtimeManager.class) {
			return ((Boolean) method.invoke(null, realtimeRuntime)).booleanValue();
		}
	}

	private static boolean startRuntimeOwned(Object realtimeRuntime, long generation) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod("startRuntimeOwnedLocked", realtimeRuntime.getClass(), long.class);
		method.setAccessible(true);
		synchronized (ProjectXRealtimeManager.class) {
			return ((Boolean) method.invoke(null, realtimeRuntime, Long.valueOf(generation))).booleanValue();
		}
	}

	private static boolean markUserHubClosed(Object realtimeRuntime, long generation, String message) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod("markUserHubClosedLocked", realtimeRuntime.getClass(), long.class, String.class);
		method.setAccessible(true);
		synchronized (ProjectXRealtimeManager.class) {
			return ((Boolean) method.invoke(null, realtimeRuntime, Long.valueOf(generation), message)).booleanValue();
		}
	}

	private static boolean userHubConnectedForAdoption(Object realtimeRuntime, long generation, boolean connected) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod("userHubConnectedForAdoptionLocked", realtimeRuntime.getClass(), long.class, boolean.class);
		method.setAccessible(true);
		synchronized (ProjectXRealtimeManager.class) {
			return ((Boolean) method.invoke(null, realtimeRuntime, Long.valueOf(generation), Boolean.valueOf(connected))).booleanValue();
		}
	}

	private static String resolveRealtimeSymbolForCallback(
		Object realtimeRuntime,
		long generation,
		String hub,
		String contractId,
		String symbol,
		String payloadJson
	) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod(
			"resolveRealtimeSymbolForCallback",
			realtimeRuntime.getClass(),
			long.class,
			String.class,
			String.class,
			String.class,
			String.class
		);
		method.setAccessible(true);
		return (String) method.invoke(null, realtimeRuntime, Long.valueOf(generation), hub, contractId, symbol, payloadJson);
	}

	private static boolean shouldPersistRealtimeEvent(long generation, String hub, String eventType, String symbol) throws Exception {
		Method method = ProjectXRealtimeManager.class.getDeclaredMethod(
			"shouldPersistRealtimeEvent",
			long.class,
			String.class,
			String.class,
			String.class
		);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, Long.valueOf(generation), hub, eventType, symbol)).booleanValue();
	}

	private static Field liveSessionField() throws Exception {
		Field field = FuturesManager.class.getDeclaredField("liveSession");
		field.setAccessible(true);
		return field;
	}

	private static Field realtimeRuntimeField() throws Exception {
		Field field = ProjectXRealtimeManager.class.getDeclaredField("runtime");
		field.setAccessible(true);
		return field;
	}

	private static Field marketGenerationCounterField() throws Exception {
		Field field = ProjectXRealtimeManager.class.getDeclaredField("marketGenerationCounter");
		field.setAccessible(true);
		return field;
	}

	private static Field staticField(String fieldName) throws Exception {
		Field field = ProjectXRealtimeManager.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return field;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Long> lastPersistedMarketEvents() throws Exception {
		return (Map<String, Long>) staticField("lastPersistedMarketEvents").get(null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> contractSymbols(Object realtimeRuntime) throws Exception {
		Field field = realtimeRuntime.getClass().getDeclaredField("contractSymbols");
		field.setAccessible(true);
		return (Map<String, String>) field.get(realtimeRuntime);
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

	private static void setString(Object target, String fieldName, String value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static boolean booleanField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.getBoolean(target);
	}

	private static long longField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.getLong(target);
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
