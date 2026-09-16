package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RuntimePathsTest {
	@TempDir
	Path tempDir;
	private String originalUserDir;

	@BeforeEach
	public void rememberUserDirectory() {
		originalUserDir = System.getProperty("user.dir");
	}

	@AfterEach
	public void clearRuntimeProperties() {
		System.clearProperty("tradingbot.runtimeRoot");
		System.clearProperty("tradingbot.runtimeRole");
		System.clearProperty("tradingbot.db.path");
		System.clearProperty("tradingbot.equityMarketDataDir");
		System.clearProperty("tradingbot.futuresDataDir");
		System.clearProperty("tradingbot.liveTradeCacheDir");
		System.setProperty("user.dir", originalUserDir);
	}

	@Test
	public void runtimeRootDrivesDbFuturesAndTradeCacheDefaults() {
		System.setProperty("tradingbot.runtimeRoot", tempDir.toString());

		assertEquals(tempDir.resolve("db/tradingbot.db").toString(), RuntimePaths.databasePath());
		assertEquals(tempDir.resolve("market_data").toString(), RuntimePaths.equityMarketDataDir());
		assertEquals(tempDir.resolve("market_data/futures").toString(), RuntimePaths.futuresDataDir());
		assertEquals(tempDir.resolve("data/live_trade_cache").toString(), RuntimePaths.liveTradeCacheDir());
	}

	@Test
	public void explicitPropertiesOverrideRuntimeRoot() {
		System.setProperty("tradingbot.runtimeRoot", tempDir.resolve("root").toString());
		System.setProperty("tradingbot.db.path", tempDir.resolve("custom/custom.db").toString());
		System.setProperty("tradingbot.equityMarketDataDir", tempDir.resolve("custom/equities").toString());
		System.setProperty("tradingbot.futuresDataDir", tempDir.resolve("custom/futures").toString());
		System.setProperty("tradingbot.liveTradeCacheDir", tempDir.resolve("custom/cache").toString());

		assertEquals(tempDir.resolve("custom/custom.db").toString(), RuntimePaths.databasePath());
		assertEquals(tempDir.resolve("custom/equities").toString(), RuntimePaths.equityMarketDataDir());
		assertEquals(tempDir.resolve("custom/futures").toString(), RuntimePaths.futuresDataDir());
		assertEquals(tempDir.resolve("custom/cache").toString(), RuntimePaths.liveTradeCacheDir());
	}

	@Test
	public void runtimeRoleDefaultsToDevAndCanBeExplicitlySet() {
		assertEquals("dev", RuntimePaths.runtimeRole());

		System.setProperty("tradingbot.runtimeRole", "live");

		assertEquals("live", RuntimePaths.runtimeRole());
	}

	@Test
	public void sharedRuntimeIsDetectedFromRuntimeRootOrExplicitPath() {
		assertFalse(RuntimePaths.usingSharedRuntime());

		System.setProperty("tradingbot.runtimeRoot", tempDir.toString());
		assertTrue(RuntimePaths.usingSharedRuntime());

		System.clearProperty("tradingbot.runtimeRoot");
		System.setProperty("tradingbot.db.path", tempDir.resolve("shared_runtime/db/tradingbot.db").toString());
		assertTrue(RuntimePaths.usingSharedRuntime());
	}

	@Test
	public void productionBackendWorkingDirectoryUsesSourceLocalLegacyFallbacks() throws Exception {
		Path productionBackend = Files.createDirectories(tempDir.resolve("production_backend"));
		System.setProperty("user.dir", productionBackend.toString());

		assertEquals(productionBackend.resolve("tradingbot.db").toString(), RuntimePaths.databasePath());
		assertEquals(productionBackend.resolve("market_data").toString(), RuntimePaths.equityMarketDataDir());
		assertEquals(productionBackend.resolve("data/live_trade_cache").toString(), RuntimePaths.liveTradeCacheDir());
	}

	@Test
	public void repositoryRootUsesIsolatedDevelopmentRuntimeFallbacks() throws Exception {
		Files.createDirectories(tempDir.resolve("production_backend"));
		System.setProperty("user.dir", tempDir.toString());

		assertEquals(tempDir.resolve("dev_runtime/db/tradingbot.db").toString(), RuntimePaths.databasePath());
		assertEquals(tempDir.resolve("dev_runtime/market_data").toString(), RuntimePaths.equityMarketDataDir());
		assertEquals(tempDir.resolve("dev_runtime/market_data/futures").toString(), RuntimePaths.futuresDataDir());
		assertEquals(tempDir.resolve("dev_runtime/data/live_trade_cache").toString(), RuntimePaths.liveTradeCacheDir());
	}

	@Test
	public void legacyBackendWorkingDirectoryStillUsesLegacyFallbacks() throws Exception {
		Path backend = Files.createDirectories(tempDir.resolve("backend"));
		System.setProperty("user.dir", backend.toString());

		assertEquals(backend.resolve("tradingbot.db").toString(), RuntimePaths.databasePath());
		assertEquals(backend.resolve("market_data").toString(), RuntimePaths.equityMarketDataDir());
	}
}
