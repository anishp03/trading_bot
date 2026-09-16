package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
	public void initializeDatabaseCreatesCoreTables() throws Exception {
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'Account'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'Strategies'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'Backtests'"));
		assertEquals(1, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'Trades'"));
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

}
