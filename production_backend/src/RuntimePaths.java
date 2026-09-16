package com.tradingbot;

import java.io.File;

final class RuntimePaths {
	private static final String DEFAULT_DB_FILE = "tradingbot.db";
	private static final String DEFAULT_EQUITY_MARKET_DATA_DIR = "market_data";
	private static final String DEFAULT_FUTURES_DATA_DIR = "market_data/futures";
	private RuntimePaths() {
	}

	static String runtimeRoot() {
		return absolutePath(firstNonBlank(
			System.getProperty("tradingbot.runtimeRoot"),
			System.getenv("TRADINGBOT_RUNTIME_ROOT")
		));
	}

	static String runtimeRole() {
		String configured = firstNonBlank(
			System.getProperty("tradingbot.runtimeRole"),
			System.getenv("TRADINGBOT_RUNTIME_ROLE")
		);
		if (isBlank(configured)) {
			return "dev";
		}
		String normalized = configured.trim().toLowerCase();
		return "live".equals(normalized) ? "live" : "dev";
	}

	static boolean usingSharedRuntime() {
		String root = runtimeRoot();
		if (!isBlank(root)) {
			return true;
		}
		return containsSharedRuntime(databasePath())
			|| containsSharedRuntime(equityMarketDataDir())
			|| containsSharedRuntime(futuresDataDir())
			|| containsSharedRuntime(liveTradeCacheDir());
	}

	static String databasePath() {
		String configured = firstNonBlank(
			System.getProperty("tradingbot.db.path"),
			System.getenv("TRADINGBOT_DB_PATH")
		);
		if (!isBlank(configured)) {
			return absolutePath(configured);
		}
		String root = runtimeRoot();
		if (!isBlank(root)) {
			return new File(root, "db/" + DEFAULT_DB_FILE).getPath();
		}
		return legacyDatabasePath();
	}

	static String futuresDataDir() {
		String configured = firstNonBlank(
			System.getProperty("tradingbot.futuresDataDir"),
			System.getenv("TRADINGBOT_FUTURES_DATA_DIR")
		);
		if (!isBlank(configured)) {
			return absolutePath(configured);
		}
		String root = runtimeRoot();
		if (!isBlank(root)) {
			return new File(root, DEFAULT_FUTURES_DATA_DIR).getPath();
		}

		File cwd = currentDirectory();
		File direct = new File(cwd, DEFAULT_FUTURES_DATA_DIR);
		if (direct.exists()) {
			return direct.getPath();
		}

		File productionBackendChild = new File(cwd, "production_backend");
		if (productionBackendChild.exists()) {
			return new File(cwd, "dev_runtime/" + DEFAULT_FUTURES_DATA_DIR).getPath();
		}

		File backendApiChild = new File(cwd, "backend_api/" + DEFAULT_FUTURES_DATA_DIR);
		if (backendApiChild.exists()) {
			return backendApiChild.getPath();
		}

		File backendChild = new File(cwd, "backend/" + DEFAULT_FUTURES_DATA_DIR);
		if (backendChild.exists()) {
			return backendChild.getPath();
		}

		return DEFAULT_FUTURES_DATA_DIR;
	}

	static String equityMarketDataDir() {
		String configured = firstNonBlank(
			System.getProperty("tradingbot.equityMarketDataDir"),
			System.getenv("TRADINGBOT_EQUITY_MARKET_DATA_DIR")
		);
		if (!isBlank(configured)) {
			return absolutePath(configured);
		}
		String root = runtimeRoot();
		if (!isBlank(root)) {
			return new File(root, DEFAULT_EQUITY_MARKET_DATA_DIR).getPath();
		}
		return legacyEquityMarketDataDir();
	}

	static String liveTradeCacheDir() {
		String configured = firstNonBlank(
			System.getProperty("tradingbot.liveTradeCacheDir"),
			System.getenv("TRADINGBOT_LIVE_TRADE_CACHE_DIR")
		);
		if (!isBlank(configured)) {
			return absolutePath(configured);
		}
		String root = runtimeRoot();
		if (!isBlank(root)) {
			return new File(root, "data/live_trade_cache").getPath();
		}
		return legacyLiveTradeCacheDir();
	}

	private static String legacyDatabasePath() {
		File currentDirectory = currentDirectory();
		if (isBackendSourceDirectory(currentDirectory)) {
			return new File(currentDirectory, DEFAULT_DB_FILE).getPath();
		}
		File productionBackend = new File(currentDirectory, "production_backend");
		if (productionBackend.exists()) {
			return new File(currentDirectory, "dev_runtime/db/" + DEFAULT_DB_FILE).getPath();
		}
		File backendApi = new File(currentDirectory, "backend_api");
		if (backendApi.exists()) {
			return new File(backendApi, DEFAULT_DB_FILE).getPath();
		}
		backendApi = new File(currentDirectory, "trading_bot/backend_api");
		if (backendApi.exists()) {
			return new File(backendApi, DEFAULT_DB_FILE).getPath();
		}
		return new File(currentDirectory, "dev_runtime/db/" + DEFAULT_DB_FILE).getPath();
	}

	private static String legacyLiveTradeCacheDir() {
		File cwd = currentDirectory();
		if (isBackendSourceDirectory(cwd) || "live_backend".equals(cwd.getName())) {
			return new File(cwd, "data/live_trade_cache").getPath();
		}
		if (new File(cwd, "production_backend").exists()) {
			return new File(cwd, "dev_runtime/data/live_trade_cache").getPath();
		}
		File base = preferredBackendSourceDirectory(cwd);
		return new File(base.exists() ? base : cwd, "data/live_trade_cache").getPath();
	}

	private static String legacyEquityMarketDataDir() {
		File currentDirectory = currentDirectory();
		if (isBackendSourceDirectory(currentDirectory)) {
			return new File(currentDirectory, DEFAULT_EQUITY_MARKET_DATA_DIR).getPath();
		}
		File productionBackend = new File(currentDirectory, "production_backend");
		if (productionBackend.exists()) {
			return new File(currentDirectory, "dev_runtime/" + DEFAULT_EQUITY_MARKET_DATA_DIR).getPath();
		}
		File backendApi = new File(currentDirectory, "backend_api");
		if (backendApi.exists()) {
			return new File(backendApi, DEFAULT_EQUITY_MARKET_DATA_DIR).getPath();
		}
		backendApi = new File(currentDirectory, "trading_bot/backend_api");
		if (backendApi.exists()) {
			return new File(backendApi, DEFAULT_EQUITY_MARKET_DATA_DIR).getPath();
		}
		return new File(currentDirectory, "dev_runtime/" + DEFAULT_EQUITY_MARKET_DATA_DIR).getPath();
	}

	private static File preferredBackendSourceDirectory(File currentDirectory) {
		File productionBackend = new File(currentDirectory, "production_backend");
		if (productionBackend.exists()) {
			return productionBackend;
		}
		File backendApi = new File(currentDirectory, "backend_api");
		return backendApi.exists() ? backendApi : new File(currentDirectory, "backend");
	}

	private static boolean isBackendSourceDirectory(File directory) {
		return "production_backend".equals(directory.getName())
			|| "backend_api".equals(directory.getName())
			|| "backend".equals(directory.getName());
	}

	private static File currentDirectory() {
		return new File(System.getProperty("user.dir")).getAbsoluteFile();
	}

	private static String firstNonBlank(String first, String second) {
		return isBlank(first) ? second : first;
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static String absolutePath(String value) {
		return isBlank(value) ? "" : new File(value.trim()).getAbsoluteFile().getPath();
	}

	private static boolean containsSharedRuntime(String value) {
		return !isBlank(value) && new File(value).getAbsolutePath().contains("/shared_runtime/");
	}
}
