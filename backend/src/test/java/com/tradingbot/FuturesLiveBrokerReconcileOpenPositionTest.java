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
}
