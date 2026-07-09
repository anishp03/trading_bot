package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FuturesLiveBrokerReconcileOpenPositionTest {
	@TempDir
	Path tempDir;

	@BeforeEach
	public void setUp() {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesManager.initializeStore();
		LiveRuntimeState.clearForTest();
	}

	@AfterEach
	public void tearDown() {
		TestDatabaseSupport.clearTempDatabase();
		LiveRuntimeState.clearForTest();
	}

	@Test
	public void pendingBrokerReconcileSuspendsSubmittedEntryFromDtmManagement() throws Exception {
		insertSnapshot();
		insertLiveDecision(
			86,
			"SUBMITTED_TOPSTEPX",
			"2026-06-16 10:26",
			"2026-06-16 10:27",
			"TopstepX order submitted from live signal (order 3137373702).",
			"{\"brokerSubmit\":{\"success\":true,\"orderId\":3137373702,\"brokerOrderId\":\"3137373702\"}}"
		);
		insertLiveDecision(
			87,
			"PENDING_BROKER_RECONCILE",
			"2026-06-16 10:27",
			"2026-06-16 10:27",
			"TopstepX reports no open broker position for MNQ, but no matching broker close fill was found yet for order 3137373702.",
			"{\"status\":\"PENDING_BROKER_RECONCILE\",\"symbol\":\"MNQ\",\"brokerOrderId\":\"3137373702\"}"
		);

		List<?> positions = liveOpenPositionsForSession(74, 47);

		assertEquals(0, positions.size(), "pending broker reconcile must stop stale local DTM/exit management");
	}

	@Test
	public void pendingBrokerReconcileStillAllowsBrokerMetricsResolution() throws Exception {
		insertSnapshot();
		insertLiveDecision(
			86,
			"SUBMITTED_TOPSTEPX",
			"2026-06-16 10:26",
			"2026-06-16 10:27",
			"TopstepX order submitted from live signal (order 3137373702).",
			"{\"brokerSubmit\":{\"success\":true,\"orderId\":3137373702,\"brokerOrderId\":\"3137373702\"}}"
		);
		insertLiveDecision(
			87,
			"PENDING_BROKER_RECONCILE",
			"2026-06-16 10:27",
			"2026-06-16 10:27",
			"TopstepX reports no open broker position for MNQ, but no matching broker close fill was found yet for order 3137373702.",
			"{\"status\":\"PENDING_BROKER_RECONCILE\",\"symbol\":\"MNQ\",\"brokerOrderId\":\"3137373702\"}"
		);
		String brokerMetrics = "{"
			+ "\"success\":true,"
			+ "\"source\":\"TOPSTEPX\","
			+ "\"positions\":[],"
			+ "\"trades\":[{"
				+ "\"accountId\":\"24175826\","
				+ "\"symbol\":\"MNQ\","
				+ "\"side\":\"BUY\","
				+ "\"closed\":true,"
				+ "\"orderId\":3137373704,"
				+ "\"createdAt\":\"2026-06-16T14:27:34.000000+00:00\","
				+ "\"price\":30695.0,"
				+ "\"pnl\":112.0,"
				+ "\"fees\":1.24"
			+ "}]"
		+ "}";

		int reconciled = reconcileBrokerFlatLiveEntries(74, 47, brokerMetrics);

		assertEquals(1, reconciled);
		assertEquals(112.0, resolvedDecisionPnl());
		assertEquals(0, liveOpenPositionsForSession(74, 47).size(), "resolved reconcile must keep DTM management closed");
	}

	@Test
	public void openOrderVerifiedEntryIsNotManagedAsOpenPosition() throws Exception {
		insertSnapshot();
		insertLiveDecision(
			86,
			"SUBMITTED_TOPSTEPX",
			"2026-06-18 15:00",
			"2026-06-18 15:01",
			"TopstepX order submitted from live signal (order 3154221481).",
			"{\"brokerSubmit\":{\"success\":true,\"orderId\":3154221481,\"brokerOrderId\":\"3154221481\","
				+ "\"verificationSource\":\"OPEN_ORDER\",\"verification\":{\"attempted\":true,\"source\":\"OPEN_ORDER\","
				+ "\"order\":{\"id\":3154221481,\"fillVolume\":0}}}}"
		);

		List<?> positions = liveOpenPositionsForSession(74, 47);

		assertEquals(0, positions.size(), "open broker order verification must not create a DTM-managed live position");
	}

	@Test
	public void partiallyFilledOpenOrderIsNotManagedAsFullOpenPosition() throws Exception {
		insertSnapshot();
		insertLiveDecision(
			86,
			"SUBMITTED_TOPSTEPX",
			"2026-06-18 15:00",
			"2026-06-18 15:01",
			"TopstepX order submitted from live signal (order 3154221481).",
			"{\"brokerSubmit\":{\"success\":true,\"orderId\":3154221481,\"brokerOrderId\":\"3154221481\","
				+ "\"verificationSource\":\"OPEN_ORDER\",\"verification\":{\"attempted\":true,\"source\":\"OPEN_ORDER\","
				+ "\"order\":{\"id\":3154221481,\"fillVolume\":1,\"filledPrice\":4236.9}}}}"
		);

		List<?> positions = liveOpenPositionsForSession(74, 47);

		assertEquals(0, positions.size(), "open-order verification must not create a full-size managed position even if broker reports a partial fill");
	}

	@Test
	public void openOrderVerifiedEntryDoesNotBecomePendingBrokerReconcile() throws Exception {
		insertSnapshot();
		insertLiveDecision(
			86,
			"SUBMITTED_TOPSTEPX",
			"2026-06-18 15:00",
			"2026-06-18 15:01",
			"TopstepX order submitted from live signal (order 3154221481).",
			"{\"brokerSubmit\":{\"success\":true,\"orderId\":3154221481,\"brokerOrderId\":\"3154221481\","
				+ "\"verificationSource\":\"OPEN_ORDER\",\"verification\":{\"attempted\":true,\"source\":\"OPEN_ORDER\","
				+ "\"order\":{\"id\":3154221481,\"fillVolume\":0}}}}"
		);
		String brokerMetrics = "{"
			+ "\"success\":true,"
			+ "\"source\":\"TOPSTEPX\","
			+ "\"positions\":[],"
			+ "\"trades\":[]"
			+ "}";

		int reconciled = reconcileBrokerFlatLiveEntries(74, 47, brokerMetrics);

		assertEquals(0, reconciled);
		assertEquals(0, countPendingBrokerReconcileRows(), "resting unfilled orders must not be misclassified as missing broker positions");
	}

	@Test
	public void restingOrderWithBrokerCloseFillReconcilesToFlatSync() throws Exception {
		insertSnapshot();
		insertLiveDecision(
			86,
			"RESTING_TOPSTEPX",
			"2026-06-26 10:22",
			"2026-06-26 10:23",
			"TopstepX order is resting from live signal with no confirmed fill (order 3191283592).",
			"{\"brokerSubmit\":{\"success\":true,\"status\":\"RESTING_TOPSTEPX\",\"orderId\":3191283592,\"brokerOrderId\":\"3191283592\","
				+ "\"customTag\":\"live-signal-MGC-omom-1782483783242\",\"bracketsSubmitted\":true,"
				+ "\"verificationSource\":\"OPEN_ORDER\",\"verification\":{\"attempted\":true,\"source\":\"OPEN_ORDER\","
				+ "\"order\":{\"id\":3191283592,\"fillVolume\":0}}},"
				+ "\"customTag\":\"live-signal-MGC-omom-1782483783242\"}"
		);
		String brokerMetrics = "{"
			+ "\"success\":true,"
			+ "\"source\":\"TOPSTEPX\","
			+ "\"positions\":[],"
			+ "\"trades\":[{"
				+ "\"accountId\":\"24175826\","
				+ "\"symbol\":\"MNQ\","
				+ "\"side\":\"BUY\","
				+ "\"closed\":true,"
				+ "\"orderId\":3191283593,"
				+ "\"createdAt\":\"2026-06-26T14:26:33.000000+00:00\","
				+ "\"price\":30752.75,"
				+ "\"pnl\":-250.0,"
				+ "\"fees\":4.35"
			+ "}]"
		+ "}";

		int reconciled = reconcileBrokerFlatLiveEntries(74, 47, brokerMetrics);

		assertEquals(1, reconciled, "later-filled RESTING_TOPSTEPX entries must reconcile from broker close fills");
		assertEquals(-250.0, resolvedDecisionPnl());
		assertEquals(0, countPendingBrokerReconcileRows(), "a matching broker close fill should not leave a pending reconcile row");
	}

	@Test
	public void restingOrderWithBrokerOpenPositionBecomesManagedPosition() throws Exception {
		insertSnapshot();
		insertLiveDecision(
			86,
			"RESTING_TOPSTEPX",
			"2026-06-26 12:52",
			"2026-06-26 12:53",
			"TopstepX order is resting from live signal with no confirmed fill (order 3192547356).",
			"{\"brokerSubmit\":{\"success\":true,\"status\":\"RESTING_TOPSTEPX\",\"orderId\":3192547356,\"brokerOrderId\":\"3192547356\","
				+ "\"customTag\":\"live-signal-MCL-ifvg-1782492781477\",\"bracketsSubmitted\":true,"
				+ "\"verificationSource\":\"OPEN_ORDER\",\"verification\":{\"attempted\":true,\"source\":\"OPEN_ORDER\","
				+ "\"order\":{\"id\":3192547356,\"fillVolume\":0}}},"
				+ "\"customTag\":\"live-signal-MCL-ifvg-1782492781477\"}"
		);
		LiveRuntimeState.updateBrokerMetricsJson("{"
			+ "\"success\":true,"
			+ "\"source\":\"TOPSTEPX\","
			+ "\"accountId\":\"24175826\","
			+ "\"positions\":[{"
				+ "\"accountId\":\"24175826\","
				+ "\"symbol\":\"MNQ\","
				+ "\"side\":\"SHORT\","
				+ "\"contracts\":2,"
				+ "\"averagePrice\":30724.5,"
				+ "\"createdAt\":\"2026-06-26T16:53:07.000000+00:00\""
			+ "}],"
			+ "\"orders\":[],"
			+ "\"trades\":[]"
			+ "}");

		List<?> positions = liveOpenPositionsForSession(74, 47);

		assertEquals(1, positions.size(), "broker-confirmed filled RESTING_TOPSTEPX entries must become DTM-managed positions");
	}

	@Test
	public void restingOrderCreatesPendingExposureReservationButNotDtmManagedPosition() throws Exception {
		Object position = portfolioPosition("MNQ", "LONG", 16, 30642.0, 30628.0, 30672.0);
		setField(position, "sourceStatus", "RESTING_TOPSTEPX");

		assertTrue(livePositionBlocksNewEntries(position), "resting entry reservations must block duplicate same-symbol candidates");
		assertFalse(livePositionIsDtmManaged(position), "unfilled resting entry reservations must not be DTM managed");
	}

	@Test
	public void pdbShortUsesMarketableProtectedLimitPrice() throws Exception {
		double brokerEntry = liveBrokerSubmitEntryPrice("PDB", "MGC", "SHORT", 4236.9, 4239.0, 4233.9, 4236.8, 4237.0);

		assertEquals(4236.6, brokerEntry, 0.000001, "PDB shorts should cross the bid with a capped protected limit");
	}

	@Test
	public void pdbLongUsesMarketableProtectedLimitPrice() throws Exception {
		double brokerEntry = liveBrokerSubmitEntryPrice("PDB", "MGC", "LONG", 4236.9, 4234.0, 4240.0, 4236.8, 4237.0);

		assertEquals(4237.2, brokerEntry, 0.000001, "PDB longs should cross the ask with a capped protected limit");
	}

	@Test
	public void nonPdbStrategyPreservesPlannedLimitPrice() throws Exception {
		double brokerEntry = liveBrokerSubmitEntryPrice("OMOM", "MGC", "SHORT", 4236.9, 4239.0, 4233.9, 4236.8, 4237.0);

		assertEquals(4236.9, brokerEntry, 0.000001, "non-PDB strategies must not inherit PDB execution aggressiveness");
	}

	@Test
	public void pdbMarketableLimitDoesNotChaseThroughTargetGeometry() throws Exception {
		double brokerEntry = liveBrokerSubmitEntryPrice("PDB", "MGC", "SHORT", 4236.9, 4239.0, 4236.65, 4236.8, 4237.0);

		assertEquals(4236.9, brokerEntry, 0.000001, "PDB should not cross the spread if the protected price would already consume the target zone");
	}

	@Test
	public void pdbRestingOrderBecomesMissedExecutionAfterCancel() throws Exception {
		String status = liveStatusAfterRestingEntryLifecycle(
			"PDB",
			"RESTING_TOPSTEPX",
			"{\"success\":true,\"ordersCanceled\":1}"
		);

		assertEquals("MISSED_EXECUTION_TOPSTEPX", status, "unfilled PDB entries must not remain as launch-equivalent resting orders");
	}

	@Test
	public void pdbRestingOrderExposesCancelFailure() throws Exception {
		String status = liveStatusAfterRestingEntryLifecycle(
			"PDB",
			"RESTING_TOPSTEPX",
			"{\"success\":false,\"ordersCanceled\":0,\"message\":\"cancel failed\"}"
		);

		assertEquals("CANCEL_FAILED_RESTING_TOPSTEPX", status, "cancel failures must be explicit and not become managed live positions");
	}

	@Test
	public void pdbRestingOrderThatFillsBeforeCancelBecomesSubmitted() throws Exception {
		String status = liveStatusAfterRestingEntryLifecycle(
			"PDB",
			"RESTING_TOPSTEPX",
			"{\"success\":true,\"filledBeforeCancel\":true,\"ordersCanceled\":0}"
		);

		assertEquals("SUBMITTED_TOPSTEPX", status, "a real broker fill found during resting-order cleanup must remain managed");
	}

	@Test
	public void nonPdbRestingOrderKeepsExistingRestingState() throws Exception {
		String status = liveStatusAfterRestingEntryLifecycle(
			"OMOM",
			"RESTING_TOPSTEPX",
			"{\"success\":true,\"ordersCanceled\":1}"
		);

		assertEquals("RESTING_TOPSTEPX", status, "non-PDB broker launch paths must keep their existing resting-order semantics");
	}

	@Test
	public void acceptedTopstepSubmitWithoutBracketsIsProtectionFailure() throws Exception {
		assertTrue(liveBrokerSubmitMissingBracketProtection(
			"{\"success\":true,\"brokerSubmitAccepted\":true,\"orderId\":3192547356,\"bracketsSubmitted\":false}"
		));
		assertFalse(liveBrokerSubmitMissingBracketProtection(
			"{\"success\":true,\"brokerSubmitAccepted\":true,\"orderId\":3192547356,\"bracketsSubmitted\":true}"
		));
	}

	@Test
	public void brokerFlatSyncLabelsProfitLockStopSeparatelyFromStopLoss() throws Exception {
		Object position = portfolioPosition("MNQ", "LONG", 2, 100.0, 101.0, 105.0);
		Object closeFill = brokerCloseFill("3192547357", 101.0, 120.0, "2026-06-26T17:10:50.000000+00:00");

		String reason = brokerFlatSyncExitReason(position, closeFill, 101.0);

		assertTrue(reason.contains("profit-lock stop fill"), reason);
	}

	@Test
	public void liveDecisionHistoryExposesDtmPnlComponents() throws Exception {
		insertSnapshot();
		insertLiveDecision(
			88,
			"FLAT_SYNC_TOPSTEPX",
			"2026-06-15 15:18",
			"2026-06-15T19:37:44.403079+00:00",
			"TopstepX matched broker close fill.",
			"{\"openedAt\":\"2026-06-15 15:18\","
				+ "\"closedAt\":\"2026-06-15T19:37:44.403079+00:00\","
				+ "\"entryPrice\":81.11,"
				+ "\"exitPrice\":81.36,"
				+ "\"pnl\":72.69,"
				+ "\"finalLegPnl\":72.69,"
				+ "\"dtmRealizedPnl\":73.56,"
				+ "\"dtmPartialContractsClosed\":3,"
				+ "\"brokerClose\":{\"success\":true,\"status\":\"FLAT_SYNC_TOPSTEPX\",\"authoritative\":true}}"
		);

		String json = FuturesManager.getLiveSignalDecisionsJson(74, 10);

		assertTrue(json.contains("\"pnl\":146.25"), json);
		assertTrue(json.contains("\"rawPnl\":72.69"), json);
		assertTrue(json.contains("\"finalLegPnl\":72.69"), json);
		assertTrue(json.contains("\"dtmRealizedPnl\":73.56"), json);
		assertTrue(json.contains("\"dtmPartialContractsClosed\":3"), json);
	}

	@Test
	public void brokerManagementBlockedStateSuppressesFurtherTopstepxDtmManagement() throws Exception {
		Object topstepSession = liveSession("TOPSTEPX");
		Object simulatedSession = liveSession("SIMULATED");
		Object state = dynamicTradeState();
		setField(state, "brokerManagementBlocked", Boolean.TRUE);

		assertTrue(dtmBrokerManagementSuspended(topstepSession, state));
		assertFalse(dtmBrokerManagementSuspended(simulatedSession, state));
		assertFalse(dtmBrokerManagementSuspended(topstepSession, dynamicTradeState()));
	}

	private static List<?> liveOpenPositionsForSession(int sessionId, int snapshotId) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveOpenPositionsForSession", int.class, int.class);
		method.setAccessible(true);
		return (List<?>) method.invoke(null, sessionId, snapshotId);
	}

	private static boolean dtmBrokerManagementSuspended(Object session, Object state) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("dtmBrokerManagementSuspended", session.getClass(), state.getClass());
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, session, state)).booleanValue();
	}

	private static double liveBrokerSubmitEntryPrice(
		String strategyCode,
		String symbol,
		String side,
		double plannedEntryPrice,
		double stopPrice,
		double targetPrice,
		double bestBid,
		double bestAsk
	) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"liveBrokerSubmitEntryPrice",
			String.class,
			String.class,
			String.class,
			double.class,
			double.class,
			double.class,
			double.class,
			double.class
		);
		method.setAccessible(true);
		return ((Double) method.invoke(null, strategyCode, symbol, side, plannedEntryPrice, stopPrice, targetPrice, bestBid, bestAsk)).doubleValue();
	}

	private static String liveStatusAfterRestingEntryLifecycle(String strategyCode, String status, String cancelJson) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"liveStatusAfterRestingEntryLifecycle",
			String.class,
			String.class,
			String.class
		);
		method.setAccessible(true);
		return (String) method.invoke(null, strategyCode, status, cancelJson);
	}

	private static boolean liveBrokerSubmitMissingBracketProtection(String responseJson) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveBrokerSubmitMissingBracketProtection", String.class);
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, responseJson)).booleanValue();
	}

	private static String brokerFlatSyncExitReason(Object position, Object closeFill, double exitPrice) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("brokerFlatSyncExitReason", position.getClass(), closeFill.getClass(), double.class);
		method.setAccessible(true);
		return (String) method.invoke(null, position, closeFill, exitPrice);
	}

	private static int reconcileBrokerFlatLiveEntries(int sessionId, int snapshotId, String brokerMetricsJson) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("reconcileBrokerFlatLiveEntries", int.class, int.class, String.class);
		method.setAccessible(true);
		return ((Integer) method.invoke(null, sessionId, snapshotId, brokerMetricsJson)).intValue();
	}

	private static Object liveSession(String executionMode) throws Exception {
		Class<?> type = Class.forName("com.tradingbot.FuturesManager$FuturesLiveSession");
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object session = constructor.newInstance();
		setField(session, "executionMode", executionMode);
		return session;
	}

	private static Object dynamicTradeState() throws Exception {
		Class<?> type = Class.forName("com.tradingbot.FuturesManager$DynamicTradeState");
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static Object portfolioPosition(String symbol, String side, int contracts, double entryPrice, double stopPrice, double targetPrice) throws Exception {
		Class<?> type = Class.forName("com.tradingbot.FuturesManager$PortfolioPosition");
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object position = constructor.newInstance();
		setField(position, "symbol", symbol);
		setField(position, "side", side);
		setField(position, "contracts", contracts);
		setField(position, "entryPrice", entryPrice);
		setField(position, "stopPrice", stopPrice);
		setField(position, "targetPrice", targetPrice);
		return position;
	}

	private static Object brokerCloseFill(String orderId, double price, double pnl, String createdAt) throws Exception {
		Class<?> type = Class.forName("com.tradingbot.FuturesManager$BrokerCloseFill");
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object fill = constructor.newInstance();
		setField(fill, "orderId", orderId);
		setField(fill, "price", price);
		setField(fill, "pnl", pnl);
		setField(fill, "createdAt", createdAt);
		return fill;
	}

	private static boolean livePositionBlocksNewEntries(Object position) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("livePositionBlocksNewEntries", position.getClass());
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, position)).booleanValue();
	}

	private static boolean livePositionIsDtmManaged(Object position) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("livePositionIsDtmManaged", position.getClass());
		method.setAccessible(true);
		return ((Boolean) method.invoke(null, position)).booleanValue();
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static void insertSnapshot() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.executeUpdate(
				"INSERT INTO FuturesLiveStrategySnapshots (snapshotID, active, symbols, fundedProfile, accountMode, practiceAccountId, maxOpenPositions, maxAggregateContracts, maxAggregateMiniUnits, strategySettingsJson, riskSettingsJson, portfolioSettingsJson, sourceMetricsJson, codeVersion, createdAt, updatedAt) "
					+ "VALUES (47, 1, 'MNQ', 'TOPSTEP_50K', 'TOPSTEP_50K', '24175826', 3, 50, 5.0, '{}', '{}', '{}', '{}', 'test', '2026-06-16 10:26', '2026-06-16 10:26')"
			);
			stmt.executeUpdate(
				"INSERT INTO FuturesLiveEngineSessions (sessionID, snapshotID, executionMode, status, symbols, dataMode, startedAt, lastUpdatedAt, lastBarTime, message) "
					+ "VALUES (74, 47, 'TOPSTEPX', 'RUNNING', 'MNQ', 'PROJECTX_SIGNALR', '2026-06-16 10:26', '2026-06-16 10:27', '2026-06-16 10:27', 'test')"
			);
		}
	}

	private static void insertLiveDecision(int decisionId, String status, String signalTime, String entryTime, String reason, String payloadJson) throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				"INSERT INTO FuturesLiveSignalDecisions (decisionID, sessionID, snapshotID, symbol, strategyCode, strategyName, side, signalTime, entryTime, contracts, entryPrice, stopPrice, targetPrice, fundedMiniUnits, status, reason, payloadJson, createdAt) "
					+ "VALUES (?, 74, 47, 'MNQ', 'OMOM', 'Compressed Opening Momentum', 'SHORT', ?, ?, 2, 30724.5, 30752.75, 30694.75, 0.2, ?, ?, ?, '2026-06-16 10:27')"
			 )) {
			stmt.setInt(1, decisionId);
			stmt.setString(2, signalTime);
			stmt.setString(3, entryTime);
			stmt.setString(4, status);
			stmt.setString(5, reason);
			stmt.setString(6, payloadJson);
			stmt.executeUpdate();
		}
	}

	private static double resolvedDecisionPnl() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				"SELECT payloadJson FROM FuturesLiveSignalDecisions WHERE sessionID = 74 AND snapshotID = 47 AND symbol = 'MNQ' AND status = 'FLAT_SYNC_TOPSTEPX' ORDER BY decisionID DESC LIMIT 1"
			 )) {
			try (java.sql.ResultSet rs = stmt.executeQuery()) {
				assertTrue(rs.next(), "expected a resolved broker flat-sync decision");
				String payload = rs.getString("payloadJson");
				Method method = FuturesManager.class.getDeclaredMethod("jsonNumber", String.class, String.class, double.class);
				method.setAccessible(true);
				return ((Double) method.invoke(null, payload, "pnl", 0.0)).doubleValue();
			}
		}
	}

	private static int countPendingBrokerReconcileRows() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				"SELECT COUNT(*) FROM FuturesLiveSignalDecisions WHERE sessionID = 74 AND snapshotID = 47 AND status = 'PENDING_BROKER_RECONCILE'"
			 )) {
			try (java.sql.ResultSet rs = stmt.executeQuery()) {
				assertTrue(rs.next(), "expected count row");
				return rs.getInt(1);
			}
		}
	}
}
