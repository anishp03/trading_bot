package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RuntimeMutationGuardTest {
	@TempDir
	Path tempDir;

	@BeforeEach
	public void useTempDatabase() {
		System.setProperty("tradingbot.db.path", tempDir.resolve("guard.db").toString());
		System.setProperty("tradingbot.runtimeRoot", tempDir.resolve("shared_runtime").toString());
		DatabaseManager.initializeDatabase();
		FuturesManager.initializeStore();
	}

	@AfterEach
	public void clearRuntimeProperties() {
		System.clearProperty("tradingbot.db.path");
		System.clearProperty("tradingbot.runtimeRoot");
		System.clearProperty("tradingbot.runtimeRole");
	}

	@Test
	public void devSharedRuntimeBlocksMarketDataImports() {
		System.setProperty("tradingbot.runtimeRole", "dev");

		RuntimeMutationGuard.Decision decision = RuntimeMutationGuard.marketDataMutationAllowed("update-backtest-data");

		assertFalse(decision.allowed);
		assertTrue(decision.message.contains("Dev runtime cannot run market-data imports"));
	}

	@Test
	public void liveSharedRuntimeAllowsMarketDataImportsWhenNoLiveSessionIsActive() {
		System.setProperty("tradingbot.runtimeRole", "live");

		RuntimeMutationGuard.Decision decision = RuntimeMutationGuard.marketDataMutationAllowed("update-backtest-data");

		assertTrue(decision.allowed, decision.message);
	}

	@Test
	public void activeLiveSessionBlocksBacktestGeneration() throws Exception {
		System.setProperty("tradingbot.runtimeRole", "live");
		insertRunningLiveSession();

		RuntimeMutationGuard.Decision decision = RuntimeMutationGuard.backtestMutationAllowed("portfolio-backtest");

		assertFalse(decision.allowed);
		assertTrue(decision.message.contains("live trading is active"));
	}

	@Test
	public void staleRunningSessionDoesNotBlockAfterLaterStoppedSession() throws Exception {
		System.setProperty("tradingbot.runtimeRole", "live");
		insertRunningLiveSession();
		insertStoppedLiveSession();

		RuntimeMutationGuard.Decision decision = RuntimeMutationGuard.backtestMutationAllowed("portfolio-backtest");

		assertTrue(decision.allowed, decision.message);
	}

	@Test
	public void historicalSubmittedLedgerDoesNotBlockBacktestGeneration() throws Exception {
		System.setProperty("tradingbot.runtimeRole", "live");
		insertLedgerRow("SUBMITTED_TOPSTEPX");

		RuntimeMutationGuard.Decision decision = RuntimeMutationGuard.backtestMutationAllowed("portfolio-backtest");

		assertTrue(decision.allowed, decision.message);
	}

	@Test
	public void pendingBrokerReconcileBlocksBacktestGeneration() throws Exception {
		System.setProperty("tradingbot.runtimeRole", "live");
		insertLedgerRow("PENDING_BROKER_RECONCILE");

		RuntimeMutationGuard.Decision decision = RuntimeMutationGuard.backtestMutationAllowed("portfolio-backtest");

		assertFalse(decision.allowed);
		assertTrue(decision.message.contains("live trading is active"));
	}

	private static void insertRunningLiveSession() throws Exception {
		insertSnapshot();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "INSERT INTO FuturesLiveEngineSessions (snapshotID, executionMode, status, symbols, dataMode, startedAt, lastUpdatedAt, lastBarTime, message) "
					 + "VALUES (1, 'LIVE_TOPSTEPX', 'RUNNING', 'MNQ', 'PROJECTX', '2026-06-06 09:45', '2026-06-06 09:46', '', 'test')")) {
			stmt.executeUpdate();
		}
	}

	private static void insertStoppedLiveSession() throws Exception {
		insertSnapshot();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				 "INSERT INTO FuturesLiveEngineSessions (snapshotID, executionMode, status, symbols, dataMode, startedAt, lastUpdatedAt, lastBarTime, message) "
					 + "VALUES (1, 'LIVE_TOPSTEPX', 'STOPPED', 'MNQ', 'PROJECTX', '2026-06-06 10:00', '2026-06-06 10:01', '', 'test stopped')")) {
			stmt.executeUpdate();
		}
	}

	private static void insertLedgerRow(String status) throws Exception {
		insertSnapshot();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement stmt = conn.prepareStatement(
				"INSERT INTO FuturesLiveOrderLedger (snapshotID, accountId, symbol, side, orderType, contracts, entryPrice, stopPrice, targetPrice, status, requestJson, responseJson, createdAt, updatedAt) "
					+ "VALUES (1, 'acct', 'MNQ', 'SHORT', 'MARKET', 1, 100.0, 101.0, 99.0, ?, '{}', '{}', '2026-06-06 09:45', '2026-06-06 09:45')")) {
			stmt.setString(1, status);
			stmt.executeUpdate();
		}
	}

	private static void insertSnapshot() throws Exception {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.executeUpdate(
				"INSERT OR IGNORE INTO FuturesLiveStrategySnapshots (snapshotID, active, symbols, fundedProfile, accountMode, createdAt, updatedAt) "
					+ "VALUES (1, 1, 'MNQ', 'TOPSTEP_50K', 'PRACTICE', '2026-06-06 09:40', '2026-06-06 09:40')"
			);
		}
	}
}
