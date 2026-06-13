package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FuturesBacktestLiveParityIntegrityTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void tearDown() {
		TestDatabaseSupport.clearTempDatabase();
	}

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
	public void riskCompressionPolicyMatchesBetweenLiveAndBacktest() throws Exception {
		assertEquals(portfolioBacktestAllowsLiveRiskCompression("ORB"), portfolioRiskCompressionAllowed("ORB"));
		assertEquals(portfolioBacktestAllowsLiveRiskCompression("ORB2"), portfolioRiskCompressionAllowed("ORB2"));
		assertEquals(portfolioBacktestAllowsLiveRiskCompression("OMOM"), portfolioRiskCompressionAllowed("OMOM"));
		assertEquals(portfolioBacktestAllowsLiveRiskCompression("PDB"), portfolioRiskCompressionAllowed("PDB"));
	}

	@Test
	public void riskConfigTickCapsOverrideRuntimeStrategySizingCaps() throws Exception {
		Object strategySettings = nestedInstance("FuturesStrategySettings");
		setField(strategySettings, "maxInitialRiskTicks", Double.valueOf(80.0));
		setField(strategySettings, "orbRetestMaxRiskTicks", Double.valueOf(80.0));
		Object riskSettings = nestedInstance("FuturesRiskSettings");
		setField(riskSettings, "maxInitialRiskTicks", Double.valueOf(220.0));
		setField(riskSettings, "orbRetestMaxRiskTicks", Double.valueOf(180.0));

		applyRiskSettingsToStrategySettings(strategySettings, riskSettings);

		assertEquals(220.0, liveInitialRiskLimitTicks(strategySettings, "ORB"), 0.0001);
		assertEquals(180.0, liveInitialRiskLimitTicks(strategySettings, "ORB2"), 0.0001);
	}

	@Test
	public void liveInitialRiskLimitUsesOrbRetestSpecificCap() throws Exception {
		Object strategySettings = nestedInstance("FuturesStrategySettings");
		setField(strategySettings, "maxInitialRiskTicks", Double.valueOf(80.0));
		setField(strategySettings, "orbRetestMaxRiskTicks", Double.valueOf(220.0));

		assertEquals(220.0, liveInitialRiskLimitTicks(strategySettings, "ORB2"), 0.0001);
	}

	@Test
	public void portfolioBacktestConfigForcesSavedRiskOff() throws Exception {
		Object config = buildPortfolioBacktestConfig("MES,MGC", true);

		assertFalse(booleanField(config, "useSavedRisk"));
	}

	@Test
	public void portfolioBacktestIntratradeDailyLossGuardKeepsHundredDollarMaeBuffer() throws Exception {
		assertEquals(100.0, staticDoubleField("PORTFOLIO_BACKTEST_MAE_RULE_BUFFER"), 0.0001);
	}

	@Test
	public void portfolioBacktestDefaultKeepsViolationTrailButTopstep50kDllIsOneThousand() throws Exception {
		String json = FuturesManager.getPortfolioBacktestDefaultConfigJson();

		assertTrue(json.contains("\"continueAfterRuleViolation\":true"), json);
		assertTrue(json.contains("\"dailyLossLimit\":1000"), json);
	}

	@Test
	public void liveRiskProfileUsesPortfolioEnvelopeInsteadOfSavedSymbolRisk() throws Exception {
		assertFalse(riskProfileUsesSavedRisk("TOPSTEP_50K"));
		assertFalse(riskProfileUsesSavedRisk("TOPSTEP_100K"));
		assertFalse(riskProfileUsesSavedRisk("TOPSTEP_150K"));
	}

	@Test
	public void riskConfigSlotSaveRoundTripsTickCaps() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesManager.FuturesRiskSettings settings = FuturesManager.loadFuturesRiskSettings("MGC", "PRESET_BESTBIASFREE");
		settings.maxInitialRiskTicks = 151.0;
		settings.orbRetestMaxRiskTicks = 177.0;

		assertTrue(FuturesManager.saveFuturesRiskSettings("MGC", "PRESET_BESTBIASFREE", settings));

		FuturesManager.FuturesRiskSettings saved = FuturesManager.loadFuturesRiskSettings("MGC", "PRESET_BESTBIASFREE");
		FuturesManager.FuturesRiskSettings defaultSlot = FuturesManager.loadFuturesRiskSettings("MGC");
		assertEquals(151.0, saved.maxInitialRiskTicks, 0.0001);
		assertEquals(177.0, saved.orbRetestMaxRiskTicks, 0.0001);
		assertEquals(220.0, defaultSlot.maxInitialRiskTicks, 0.0001);
		assertEquals(220.0, defaultSlot.orbRetestMaxRiskTicks, 0.0001);
	}

	@Test
	public void strategyConfigSaveDoesNotMutateRiskConfigSlot() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesManager.FuturesRiskSettings riskSettings = FuturesManager.loadFuturesRiskSettings("MNQ", "PRESET_BIASFREE92K");
		riskSettings.maxRiskPerTrade = 123.0;
		riskSettings.maxInitialRiskTicks = 151.0;
		riskSettings.orbRetestMaxRiskTicks = 177.0;
		assertTrue(FuturesManager.saveFuturesRiskSettings("MNQ", "PRESET_BIASFREE92K", riskSettings));

		FuturesManager.FuturesStrategySettings strategySettings = FuturesManager.loadFuturesStrategySettings("MNQ", "PRESET_BIASFREE92K");
		strategySettings.orb.maxTradesPerDay = 2;
		strategySettings.maxInitialRiskTicks = 99.0;
		strategySettings.orbRetestMaxRiskTicks = 88.0;
		assertTrue(FuturesManager.saveFuturesStrategySettings("MNQ", "PRESET_BIASFREE92K", strategySettings));

		FuturesManager.FuturesRiskSettings savedRisk = FuturesManager.loadFuturesRiskSettings("MNQ", "PRESET_BIASFREE92K");
		FuturesManager.FuturesStrategySettings savedStrategy = FuturesManager.loadFuturesStrategySettings("MNQ", "PRESET_BIASFREE92K");
		assertEquals(123.0, savedRisk.maxRiskPerTrade, 0.0001);
		assertEquals(151.0, savedRisk.maxInitialRiskTicks, 0.0001);
		assertEquals(177.0, savedRisk.orbRetestMaxRiskTicks, 0.0001);
		assertEquals(99.0, savedStrategy.maxInitialRiskTicks, 0.0001);
		assertEquals(88.0, savedStrategy.orbRetestMaxRiskTicks, 0.0001);
	}

	@Test
	public void topstepFundedProfileAccountIdsMatchActiveProjectxAccounts() throws Exception {
		assertEquals("24175826", accountIdForFundedProfile("TOPSTEP_50K"));
		assertEquals("24154520", accountIdForFundedProfile("TOPSTEP_150K"));
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

	@Test
	public void previousRecordedRealtimeDayUsesCapturedMinuteBars() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesMarketDataStore.initializeStore();
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			for (int minute = 0; minute < 65; minute++) {
				int absoluteMinute = 30 + minute;
				String timestamp = String.format(
					"2026-06-08T%02d:%02d:00Z",
					Integer.valueOf(13 + (absoluteMinute / 60)),
					Integer.valueOf(absoluteMinute % 60)
				);
				stmt.executeUpdate(
					"INSERT INTO FuturesLiveCapturedBars (symbol, timestamp, open, high, low, close, volume, updatedAt) VALUES "
						+ "('MES', '" + timestamp + "', 7400.0, 7401.0, 7399.0, 7400.5, 1.0, 'test')"
				);
			}
		}

		LocalDate previousDay = previousRecordedRealtimeDay("MES", LocalDate.parse("2026-06-09"));

		assertEquals(LocalDate.parse("2026-06-08"), previousDay);
	}

	@Test
	public void backtestParityPolicyDoesNotBlockOmomForLiveEntryDecay() throws Exception {
		Object context = portfolioSymbolContext("MES", 1.0);
		Object event = signalEvent("MES", "OMOM", "Compressed Opening Momentum", "SHORT", "2026-06-09", "10:42", 0);
		Object signal = field(event, "signal");
		setField(signal, "entryPrice", Double.valueOf(7430.5));
		setField(signal, "stopPrice", Double.valueOf(7437.75));
		setField(signal, "targetPrice", Double.valueOf(7426.51));
		Object executionBar = bar("2026-06-09 10:42", 7429.75);
		setField(executionBar, "open", Double.valueOf(7429.75));
		setField(executionBar, "high", Double.valueOf(7430.0));
		setField(executionBar, "low", Double.valueOf(7428.0));
		setField(executionBar, "close", Double.valueOf(7429.75));

		String reason = liveEntryDecayRejectReason(context, event, executionBar, "BACKTEST_PARITY");

		assertEquals("", reason);
	}

	@Test
	public void liveStrictPolicyStillBlocksOmomForEntryDecay() throws Exception {
		Object context = portfolioSymbolContext("MES", 1.0);
		Object event = signalEvent("MES", "OMOM", "Compressed Opening Momentum", "SHORT", "2026-06-09", "10:42", 0);
		Object signal = field(event, "signal");
		setField(signal, "entryPrice", Double.valueOf(7430.5));
		setField(signal, "stopPrice", Double.valueOf(7437.75));
		setField(signal, "targetPrice", Double.valueOf(7426.51));
		Object executionBar = bar("2026-06-09 10:42", 7429.75);
		setField(executionBar, "open", Double.valueOf(7429.75));
		setField(executionBar, "high", Double.valueOf(7430.0));
		setField(executionBar, "low", Double.valueOf(7428.0));
		setField(executionBar, "close", Double.valueOf(7429.75));

		String reason = liveEntryDecayRejectReason(context, event, executionBar, "LIVE_STRICT");

		assertTrue(reason.contains("consumed too much reward"), reason);
	}

	@Test
	public void plannedBacktestEntrySizingAcceptsOmomDecisionGeometry() throws Exception {
		Object context = portfolioSymbolContext("MES", 1.0);
		Object event = signalEvent("MES", "OMOM", "Compressed Opening Momentum", "SHORT", "2026-06-09", "10:42", 0);
		Object signal = field(event, "signal");
		setField(signal, "entryPrice", Double.valueOf(7430.5));
		setField(signal, "stopPrice", Double.valueOf(7437.75));
		setField(signal, "targetPrice", Double.valueOf(7426.51));
		Object entryBar = bar("2026-06-09 10:42", 7430.5);

		Object position = openPortfolioPosition(context, event, entryBar, 700.0, 50, 1000.0, false);

		assertNotNull(position);
		assertTrue(doubleField(position, "rawRiskTicks") <= 32.0, "rawRiskTicks=" + doubleField(position, "rawRiskTicks"));
		assertTrue(intField(position, "contracts") >= 1, "contracts=" + intField(position, "contracts"));
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

	private static void applyRiskSettingsToStrategySettings(Object strategySettings, Object riskSettings) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"applyRiskSettingsToStrategySettings",
			strategySettings.getClass(),
			riskSettings.getClass()
		);
		method.setAccessible(true);
		method.invoke(null, strategySettings, riskSettings);
	}

	private static double liveInitialRiskLimitTicks(Object strategySettings, String strategyCode) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveInitialRiskLimitTicks", strategySettings.getClass(), String.class);
		method.setAccessible(true);
		return ((Double) method.invoke(null, strategySettings, strategyCode)).doubleValue();
	}

	private static boolean riskProfileUsesSavedRisk(String profile) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("riskProfileUsesSavedRisk", String.class);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, profile)).booleanValue();
	}

	private static Object buildPortfolioBacktestConfig(String symbols, boolean useSavedRisk) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"buildPortfolioBacktestConfig",
			String.class,
			String.class,
			String.class,
			double.class,
			double.class,
			double.class,
			double.class,
			int.class,
			double.class,
			double.class,
			int.class,
			int.class,
			double.class,
			boolean.class,
			double.class,
			String.class
		);
		method.setAccessible(true);
		return method.invoke(
			null,
			symbols,
			"2026-06-01",
			"2026-06-10",
			Double.valueOf(50000.0),
			Double.valueOf(2000.0),
			Double.valueOf(1000.0),
			Double.valueOf(700.0),
			Integer.valueOf(50),
			Double.valueOf(1.24),
			Double.valueOf(1.0),
			Integer.valueOf(3),
			Integer.valueOf(50),
			Double.valueOf(5.0),
			Boolean.valueOf(useSavedRisk),
			Double.valueOf(0.0),
			"TOPSTEP_50K"
		);
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

	private static String liveEntryDecayRejectReason(Object context, Object event, Object executionBar, String policyName) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"liveEntryDecayRejectReason",
			context.getClass(),
			event.getClass(),
			executionBar.getClass(),
			String.class
		);
		method.setAccessible(true);
		return (String) method.invoke(null, context, event, executionBar, policyName);
	}

	private static LocalDate previousRecordedRealtimeDay(String symbol, LocalDate currentDay) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("previousRecordedRealtimeDay", String.class, LocalDate.class);
		method.setAccessible(true);
		return (LocalDate) method.invoke(null, symbol, currentDay);
	}

	private static Object openPortfolioPosition(Object context, Object event, Object entryBar, double riskBudget, int aggregateRoom, double aggregateGuardBudget, boolean allowLiveRiskCompression) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"openPortfolioPosition",
			context.getClass(),
			event.getClass(),
			entryBar.getClass(),
			double.class,
			int.class,
			double.class,
			boolean.class
		);
		method.setAccessible(true);
		return method.invoke(
			null,
			context,
			event,
			entryBar,
			Double.valueOf(riskBudget),
			Integer.valueOf(aggregateRoom),
			Double.valueOf(aggregateGuardBudget),
			Boolean.valueOf(allowLiveRiskCompression)
		);
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
		setField(config, "commissionPerContract", Double.valueOf(1.24));
		setField(config, "maxContracts", Integer.valueOf(50));
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

	private static Object signalEvent(String symbol, String strategyCode, String strategyName, String side, String day, String entryTime, int executionIndex) throws Exception {
		Object event = nestedInstance("SignalEvent");
		Object signal = signal(strategyCode, strategyName, side);
		setField(event, "symbol", symbol);
		setField(event, "signal", signal);
		setField(event, "day", java.time.LocalDate.parse(day));
		setField(event, "entryTime", java.time.LocalTime.parse(entryTime));
		setField(event, "executionIndex", Integer.valueOf(executionIndex));
		return event;
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
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

	private static boolean booleanField(Object target, String fieldName) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return ((Boolean) field.get(target)).booleanValue();
	}

	private static double staticDoubleField(String fieldName) throws Exception {
		Field field = FuturesManager.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return ((Double) field.get(null)).doubleValue();
	}
}
