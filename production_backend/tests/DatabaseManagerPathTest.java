package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DatabaseManagerPathTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void clearRuntimeProperties() {
		System.clearProperty("tradingbot.runtimeRoot");
		System.clearProperty("tradingbot.db.path");
	}

	@Test
	public void databaseManagerUsesRuntimeRootDbPath() {
		System.setProperty("tradingbot.runtimeRoot", tempDir.toString());

		assertEquals(
			tempDir.resolve("db/tradingbot.db").toAbsolutePath().toString(),
			DatabaseManager.getDatabasePath()
		);
	}

	@Test
	public void databaseParentDirectoryIsCreatedOnInitialize() {
		Path dbPath = tempDir.resolve("nested/db/tradingbot.db");
		System.setProperty("tradingbot.db.path", dbPath.toString());

		DatabaseManager.initializeDatabase();

		assertTrue(Files.isRegularFile(dbPath));
	}
}
