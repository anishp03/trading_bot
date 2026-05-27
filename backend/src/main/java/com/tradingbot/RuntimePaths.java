package com.tradingbot;

import java.io.File;

final class RuntimePaths {
	private static final String DEFAULT_FUTURES_DATA_DIR = "market_data/futures";

	private RuntimePaths() {
	}

	static String futuresDataDir() {
		String configured = firstNonBlank(
			System.getProperty("tradingbot.futuresDataDir"),
			System.getenv("TRADINGBOT_FUTURES_DATA_DIR")
		);
		if (!isBlank(configured)) {
			return configured;
		}

		File cwd = new File("").getAbsoluteFile();
		File direct = new File(cwd, DEFAULT_FUTURES_DATA_DIR);
		if (direct.exists()) {
			return direct.getPath();
		}

		File backendChild = new File(cwd, "backend/" + DEFAULT_FUTURES_DATA_DIR);
		if (backendChild.exists()) {
			return backendChild.getPath();
		}

		return DEFAULT_FUTURES_DATA_DIR;
	}

	private static String firstNonBlank(String first, String second) {
		return isBlank(first) ? second : first;
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
