package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FuturesManagerRuntimePathsTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void clearRuntimeProperties() {
		System.clearProperty("tradingbot.liveTradeCacheDir");
	}

	@Test
	public void liveTradeCacheUsesRuntimePathOverride() throws Exception {
		Path cacheDir = tempDir.resolve("runtime-cache");
		System.setProperty("tradingbot.liveTradeCacheDir", cacheDir.toString());

		assertEquals(cacheDir.toString(), liveTradeCacheDir().getPath());
	}

	private static File liveTradeCacheDir() throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("liveTradeCacheDir");
		method.setAccessible(true);
		return (File) method.invoke(null);
	}
}
