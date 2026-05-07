package com.tradingbot;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class TestDatabaseSupport {
	private static final String TEST_DB_PROPERTY = "tradingbot.db.path";

	private TestDatabaseSupport() {
	}

	static void useTempDatabase(Path tempDir) {
		System.setProperty(TEST_DB_PROPERTY, tempDir.resolve("tradingbot-test.db").toAbsolutePath().toString());
		DatabaseManager.initializeDatabase();
	}

	static void clearTempDatabase() {
		System.clearProperty(TEST_DB_PROPERTY);
	}

	static int countRows(String sql) throws SQLException {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : 0;
		}
	}
}
