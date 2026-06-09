package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

public class FuturesBacktestLiveParityIntegrityTest {
	@Test
	public void backtestExposureGuardRejectsOpenCorrelatedFamily() throws Exception {
		List<Object> openPositions = new ArrayList<Object>();
		openPositions.add(portfolioPosition("MES", "SHORT", "CMOM", 7419.5, 7432.0, 7408.25, 3));

		assertTrue(backtestHasCorrelatedPortfolioExposure(openPositions, "ES"));
		assertTrue(backtestHasCorrelatedPortfolioExposure(openPositions, "MES"));
	}

	@Test
	public void dtmRunnerTradeCarriesReconciliationFieldsAndSourceStrategy() throws Exception {
		Object signal = signal("LIQREC", "Liquidity Reclaim", "SHORT");
		setField(signal, "sourceStrategyCode", "AFT");
		setField(signal, "sourceStrategyName", "Afternoon Continuation");
		Object position = portfolioPosition("MES", "SHORT", signal, 100.0, 104.0, 95.0, 2);
		setField(position, "dtmRealizedPnl", Double.valueOf(100.0));
		setField(position, "dtmPartialContractsClosed", Integer.valueOf(2));
		setField(position, "dtmFinalAction", "DTM_PARTIAL_HALF_RUNNER_EXTENDED");

		Object context = portfolioSymbolContext("MES", 0.0);
		Object exitBar = bar("2026-06-05 10:00", 95.0);
		Object trade = buildPortfolioTrade(position, context, exitBar, 1, 95.0, "Target reached");

		assertEquals("AFT", stringField(trade, "sourceStrategyCode"));
		assertEquals("Afternoon Continuation", stringField(trade, "sourceStrategyName"));
		assertEquals(100.0, doubleField(trade, "dtmRealizedPnl"), 0.0001);
		assertEquals(45.04, doubleField(trade, "finalLegPnl"), 0.0001);
		assertEquals(145.04, doubleField(trade, "pnl"), 0.0001);
		assertEquals(2, intField(trade, "dtmPartialContractsClosed"));
	}

	@Test
	public void liveRealizedPnlIgnoresNonAuthoritativeBrokerCloseSummaries() throws Exception {
		assertEquals(
			0.0,
			liveDecisionRealizedPnl("CLOSED_TOPSTEPX", "{\"pnl\":587.56,\"exitPrice\":29810.75}"),
			0.0001
		);
		assertEquals(
			202.88,
			liveDecisionRealizedPnl(
				"FLAT_SYNC_TOPSTEPX",
				"{\"pnl\":202.88,\"brokerClose\":{\"authoritative\":true,\"source\":\"TOPSTEPX_METRICS_RECONCILE\"}}"
			),
			0.0001
		);
			assertEquals(
				75.0,
				liveDecisionRealizedPnl("SIMULATED_TARGET_EXIT", "{\"pnl\":75.0}"),
				0.0001
			);
			assertTrue(!liveDecisionPnlAuthoritative("CLOSED_TOPSTEPX", "{\"pnl\":587.56,\"exitPrice\":29810.75}"));
			assertTrue(liveDecisionPnlAuthoritative(
				"FLAT_SYNC_TOPSTEPX",
				"{\"pnl\":202.88,\"brokerClose\":{\"authoritative\":true,\"source\":\"TOPSTEPX_METRICS_RECONCILE\"}}"
			));
	}

	@Test
	public void liveCycleAuditPayloadIncludesBackendOnlyTimingDiagnostics() throws Exception {
		Object session = nestedInstance("FuturesLiveSession");
		setField(session, "sessionId", Integer.valueOf(41));
		setField(session, "strategyPreset", "bestbiasfree");
		setField(session, "strategySlot", "PRESET_BESTBIASFREE");
		setField(session, "fundedProfile", "TOPSTEP_50K");
		setField(session, "executionMode", "TOPSTEPX");

		Object snapshot = nestedInstance("LiveStrategySnapshotRow");
		setField(snapshot, "snapshotId", Integer.valueOf(14));

		Object marketStatus = nestedInstance("MarketSessionStatus");
		setField(marketStatus, "code", "RTH_OPEN");
		setField(marketStatus, "marketDate", "2026-06-09");
		setField(marketStatus, "entryWindowOpen", Boolean.TRUE);

		Object feed = nestedInstance("MarketFeedFreshness");
		setField(feed, "fresh", Boolean.TRUE);
		setField(feed, "eventAgeSeconds", Long.valueOf(7L));
		setField(feed, "reason", "ProjectX feed fresh.");

		String payload = liveCycleAuditPayloadJson(
			session,
			snapshot,
			marketStatus,
			feed,
			new ArrayList<String>(),
			new ArrayList<String>(),
			1250L,
			62L,
			810L,
			233L
		);

		assertTrue(payload.contains("\"cycleDurationMs\":1250"), payload);
		assertTrue(payload.contains("\"latestBarAgeSeconds\":62"), payload);
		assertTrue(payload.contains("\"latestBarLagSeconds\":62"), payload);
		assertTrue(payload.contains("\"feedAgeSeconds\":7"), payload);
		assertTrue(payload.contains("\"brokerExposureDurationMs\":810"), payload);
		assertTrue(payload.contains("\"strategyScanDurationMs\":233"), payload);
	}

	@Test
	public void portfolioRiskCompressionPolicyRejectsOrbInBacktestAndLive() throws Exception {
		assertFalse(portfolioRiskCompressionAllowed("ORB"));
		assertFalse(portfolioRiskCompressionAllowed("ORB2"));
		assertFalse(portfolioRiskCompressionAllowed("CMOM"));
		assertFalse(portfolioRiskCompressionAllowed("PDB"));
	}

	@Test
	public void portfolioBacktestRejectsLiveCompressionForOrb() throws Exception {
		assertFalse(portfolioBacktestAllowsLiveRiskCompression("ORB"));
		assertFalse(portfolioBacktestAllowsLiveRiskCompression("ORB2"));
		assertFalse(portfolioBacktestAllowsLiveRiskCompression("CMOM"));
		assertFalse(portfolioBacktestAllowsLiveRiskCompression("PDB"));
	}

	@Test
	public void liveStartCanReuseMatchingPromotedSourceSnapshot() throws Exception {
		String accountId = accountIdForFundedProfile("TOPSTEP_50K");
		Object snapshot = nestedInstance("LiveStrategySnapshotRow");
		setField(snapshot, "sourcePortfolioBacktestId", Integer.valueOf(42));
		setField(snapshot, "symbols", "MES,MNQ");
		setField(snapshot, "fundedProfile", "TOPSTEP_50K");
		setField(snapshot, "practiceAccountId", accountId);
		setField(snapshot, "portfolioSettingsJson", "{\"strategyPreset\":\"bestbiasfree\",\"strategySlot\":\"PRESET_BESTBIASFREE\"}");

		assertTrue(liveSourceSnapshotMatchesPreset(snapshot, "MES,MNQ", "TOPSTEP_50K", "bestbiasfree", "PRESET_BESTBIASFREE", accountId));
		assertFalse(liveSourceSnapshotMatchesPreset(snapshot, "MES,MGC", "TOPSTEP_50K", "bestbiasfree", "PRESET_BESTBIASFREE", accountId));

		setField(snapshot, "sourcePortfolioBacktestId", Integer.valueOf(0));
		assertFalse(liveSourceSnapshotMatchesPreset(snapshot, "MES,MNQ", "TOPSTEP_50K", "bestbiasfree", "PRESET_BESTBIASFREE", accountId));
	}

	@Test
	public void staleSignalReasonLabelsRawPreSizingSignals() throws Exception {
		String reason = staleSignalSkippedReason(false);

		assertTrue(reason.contains("raw strategy signal"), reason);
		assertTrue(reason.contains("not yet passed live sizing"), reason);
		assertTrue(reason.contains("catch-up orders are disabled"), reason);
	}

	private static boolean backtestHasCorrelatedPortfolioExposure(List<Object> positions, String symbol) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("backtestHasCorrelatedPortfolioExposure", List.class, String.class);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, positions, symbol)).booleanValue();
	}

	private static double liveDecisionRealizedPnl(String status, String payloadJson) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveDecisionRealizedPnl", String.class, String.class);
		method.setAccessible(true);
		return ((Double) method.invoke(null, status, payloadJson)).doubleValue();
	}

	private static boolean liveDecisionPnlAuthoritative(String status, String payloadJson) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveDecisionPnlAuthoritative", String.class, String.class);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, status, payloadJson)).booleanValue();
	}

	private static boolean portfolioBacktestAllowsLiveRiskCompression(String strategyCode) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("portfolioBacktestAllowsLiveRiskCompression", String.class);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, strategyCode)).booleanValue();
	}

	private static boolean portfolioRiskCompressionAllowed(String strategyCode) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("portfolioRiskCompressionAllowed", String.class);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, strategyCode)).booleanValue();
	}

	private static boolean liveSourceSnapshotMatchesPreset(
		Object snapshot,
		String symbols,
		String fundedProfile,
		String strategyPreset,
		String strategySlot,
		String accountId
	) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"liveSourceSnapshotMatchesPreset",
			snapshot.getClass(),
			String.class,
			String.class,
			String.class,
			String.class,
			String.class
		);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, snapshot, symbols, fundedProfile, strategyPreset, strategySlot, accountId)).booleanValue();
	}

	private static String accountIdForFundedProfile(String profile) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("accountIdForFundedProfile", String.class);
		method.setAccessible(true);
		return (String) method.invoke(null, profile);
	}

	private static String staleSignalSkippedReason(boolean validated) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("staleSignalSkippedReason", boolean.class);
		method.setAccessible(true);
		return (String) method.invoke(null, Boolean.valueOf(validated));
	}

	private static String liveCycleAuditPayloadJson(
		Object session,
		Object snapshot,
		Object marketStatus,
		Object feed,
		List<String> symbolAuditParts,
		List<String> candidateAuditParts,
		long cycleDurationMs,
		long latestBarAgeSeconds,
		long brokerExposureDurationMs,
		long strategyScanDurationMs
	) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"liveCycleAuditPayloadJson",
			session.getClass(),
			snapshot.getClass(),
			marketStatus.getClass(),
			feed.getClass(),
			List.class,
			List.class,
			long.class,
			long.class,
			long.class,
			long.class
		);
		method.setAccessible(true);
		return (String) method.invoke(
			null,
			session,
			snapshot,
			marketStatus,
			feed,
			symbolAuditParts,
			candidateAuditParts,
			Long.valueOf(cycleDurationMs),
			Long.valueOf(latestBarAgeSeconds),
			Long.valueOf(brokerExposureDurationMs),
			Long.valueOf(strategyScanDurationMs)
		);
	}

	private static Object buildPortfolioTrade(Object position, Object context, Object bar, int exitIndex, double rawExitPrice, String exitReason) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"buildPortfolioTrade",
			position.getClass(),
			context.getClass(),
			bar.getClass(),
			int.class,
			double.class,
			String.class
		);
		method.setAccessible(true);
		return method.invoke(null, position, context, bar, Integer.valueOf(exitIndex), Double.valueOf(rawExitPrice), exitReason);
	}

	private static Object portfolioPosition(String symbol, String side, String strategyCode, double entry, double stop, double target, int contracts) throws Exception {
		return portfolioPosition(symbol, side, signal(strategyCode, strategyCode, side), entry, stop, target, contracts);
	}

	private static Object portfolioPosition(String symbol, String side, Object signal, double entry, double stop, double target, int contracts) throws Exception {
		Object position = nestedInstance("PortfolioPosition");
		setField(position, "symbol", symbol);
		setField(position, "spec", instrumentFor(symbol));
		setField(position, "signal", signal);
		setField(position, "side", side);
		setField(position, "contracts", Integer.valueOf(contracts));
		setField(position, "originalContracts", Integer.valueOf(contracts));
		setField(position, "entryPrice", Double.valueOf(entry));
		setField(position, "stopPrice", Double.valueOf(stop));
		setField(position, "targetPrice", Double.valueOf(target));
		setField(position, "activeStopPrice", Double.valueOf(stop));
		setField(position, "commissionPerContract", Double.valueOf(1.24));
		setField(position, "openedAt", "2026-06-05 09:59");
		return position;
	}

	private static Object portfolioSymbolContext(String symbol, double slippageTicks) throws Exception {
		Object context = nestedInstance("PortfolioSymbolContext");
		Object config = nestedInstance("BacktestConfig");
		setField(config, "slippageTicks", Double.valueOf(slippageTicks));
		setField(context, "symbol", symbol);
		setField(context, "spec", instrumentFor(symbol));
		setField(context, "config", config);
		return context;
	}

	private static Object signal(String strategyCode, String strategyName, String side) throws Exception {
		Object signal = nestedInstance("Signal");
		setField(signal, "strategyCode", strategyCode);
		setField(signal, "strategyName", strategyName);
		setField(signal, "side", side);
		return signal;
	}

	private static Object bar(String displayTime, double close) throws Exception {
		Object bar = nestedInstance("Bar");
		setField(bar, "displayTime", displayTime);
		setField(bar, "marketDate", java.time.LocalDate.parse(displayTime.substring(0, 10)));
		setField(bar, "marketTime", java.time.LocalTime.parse(displayTime.substring(11)));
		setField(bar, "open", Double.valueOf(close));
		setField(bar, "high", Double.valueOf(close));
		setField(bar, "low", Double.valueOf(close));
		setField(bar, "close", Double.valueOf(close));
		return bar;
	}

	private static Object instrumentFor(String symbol) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("instrumentFor", String.class);
		method.setAccessible(true);
		return method.invoke(null, symbol);
	}

	private static Object nestedInstance(String simpleName) throws Exception {
		Class<?> type = Class.forName("com.tradingbot.FuturesManager$" + simpleName);
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
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

	private static int intField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return ((Integer) field.get(target)).intValue();
	}

	private static double doubleField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return ((Double) field.get(target)).doubleValue();
	}
}
