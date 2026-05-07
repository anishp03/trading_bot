package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DatabaseManagerTest {
	@TempDir
	Path tempDir;

	@BeforeEach
	public void setUp() {
		TestDatabaseSupport.useTempDatabase(tempDir);
	}

	@AfterEach
	public void tearDown() {
		TestDatabaseSupport.clearTempDatabase();
	}

	@Test
	public void databaseConnectionCanOpenConfiguredTestDatabase() {
		assertDoesNotThrow(() -> {
			Connection conn = DatabaseManager.getConnection();
			assertNotNull(conn, "The database connection should not be null.");
			conn.close();
		});
	}

	@Test
	public void initializeDatabaseCreatesCoreTablesAndDefaultStrategies() throws Exception {
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'Account'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'Strategies'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'Backtests'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'Trades'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM Strategies WHERE strategyCode = 'ORB'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM Strategies WHERE strategyCode = 'IFVG'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM Strategies WHERE strategyCode = 'VWAP'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM Strategies WHERE strategyCode = 'GAPGO'"));
	}

	@Test
	public void liveBotRunAndTradeLifecyclePersistsRoundedValues() throws Exception {
		int liveBotId = DatabaseManager.createLiveBotRun(-1, " tsla ", "2026-04-26 10:00", 1000.236);
		assertTrue(liveBotId > 0);

		assertTrue(DatabaseManager.updateLiveBotRun(liveBotId, "STOPPED", "2026-04-26 10:01", 999.999, -12.345, -1.234, 50.555, 2));
		int tradeId = DatabaseManager.insertLiveTrade(liveBotId, "aapl", "LONG", 3.456, 101.239, "2026-04-26 10:02", "ORB", "Opening Range Breakout", "Opened from unit test", "OPEN");
		assertTrue(tradeId > 0);
		assertTrue(DatabaseManager.closeLiveTrade(tradeId, 105.555, "2026-04-26 10:05", "Closed from unit test", "CLOSED", 12.345));

		try (Connection conn = DatabaseManager.getConnection()) {
			try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM Live_Bot WHERE liveBotID = ?")) {
				pstmt.setInt(1, liveBotId);
				try (ResultSet rs = pstmt.executeQuery()) {
					assertTrue(rs.next());
					assertEquals("TSLA", rs.getString("symbols"));
					assertEquals("STOPPED", rs.getString("status"));
					assertEquals(1000.00, rs.getDouble("equity"), 0.001);
					assertEquals(-12.34, rs.getDouble("totalProfit"), 0.001);
					assertEquals(-1.23, rs.getDouble("returnPct"), 0.001);
					assertEquals(50.56, rs.getDouble("winRate"), 0.001);
					assertEquals(2, rs.getInt("numTrades"));
				}
			}

			try (PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM Trades WHERE tradeID = ?")) {
				pstmt.setInt(1, tradeId);
				try (ResultSet rs = pstmt.executeQuery()) {
					assertTrue(rs.next());
					assertEquals("AAPL", rs.getString("symbol"));
					assertEquals("ORB", rs.getString("strategyCode"));
					assertEquals("Opening Range Breakout", rs.getString("strategyName"));
					assertEquals("CLOSED", rs.getString("status"));
					assertEquals(3.46, rs.getDouble("qty"), 0.001);
					assertEquals(101.24, rs.getDouble("entryPrice"), 0.001);
					assertEquals(105.56, rs.getDouble("exitPrice"), 0.001);
					assertEquals(12.35, rs.getDouble("pnl"), 0.001);
				}
			}
		}
	}

	@Test
	public void clearBacktestsDeletesBacktestRunsAndPreservesLiveTrades() throws Exception {
		int liveBotId = DatabaseManager.createLiveBotRun(-1, "SPY", "2026-04-26 09:30", 25000.0);
		int liveTradeId = DatabaseManager.insertLiveTrade(liveBotId, "SPY", "LONG", 1.0, 500.0, "2026-04-26 09:31", "IFVG", "Inverse Fair Value Gap", "Live trade", "OPEN");

		try (Connection conn = DatabaseManager.getConnection()) {
			int backtestId;
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate("INSERT INTO Backtests (backtestName, symbols, timeframe, startDate, endDate, startingCapital, endingCapital, totalProfit, returnPct, winRate, numTrades, profitFactor, maxDrawdownPct, createdAt) VALUES ('run_test_manual', 'SPY', '1Min', '2024-04-22', '2024-04-22', 25000, 25100, 100, 0.4, 100, 1, 100, 0, '2026-04-26T00:00:00Z')");
			}
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT backtestID FROM Backtests LIMIT 1")) {
				assertTrue(rs.next());
				backtestId = rs.getInt(1);
			}
			try (PreparedStatement pstmt = conn.prepareStatement("INSERT INTO Trades (backtestID, symbol, side, qty, entryPrice, exitPrice, openedAt, closedAt, tradeNotes, status, pnl) VALUES (?, 'SPY', 'LONG', 1, 500, 501, 'open', 'close', 'Backtest trade', 'CLOSED', 1)")) {
				pstmt.setInt(1, backtestId);
				pstmt.executeUpdate();
			}
		}

		assertTrue(liveTradeId > 0);
		assertTrue(DatabaseManager.clearBacktests());
		assertEquals(0, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM Backtests"));
		assertEquals(0, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM Trades WHERE backtestID IS NOT NULL"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM Trades WHERE liveBotID IS NOT NULL"));
	}

	@Test
	public void generateStrategyBacktestStoresRunForCachedMarketData() throws Exception {
		int backtestId = DatabaseManager.generateStrategyBacktest("spy", "2024-04-22", "2024-04-22", 25000.0, 5000.0, 100.0, 50.0);
		assertTrue(backtestId > 0);

		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement("SELECT * FROM Backtests WHERE backtestID = ?")) {
			pstmt.setInt(1, backtestId);
			try (ResultSet rs = pstmt.executeQuery()) {
				assertTrue(rs.next());
				assertEquals("run_test_1", rs.getString("backtestName"));
				assertEquals("SPY", rs.getString("symbols"));
				assertEquals("2024-04-22", rs.getString("startDate"));
				assertEquals("2024-04-22", rs.getString("endDate"));
				assertEquals(25000.0, rs.getDouble("startingCapital"), 0.001);
				assertTrue(rs.getString("timeframe").contains("5Min"));
			}
		}
	}
}
