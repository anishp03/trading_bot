package com.tradingbot;

public class ExactImplementationBacktestRunner {
	public static void main(String[] args) {
		if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
			System.setProperty("tradingbot.db.path", args[0].trim());
		}
		if (args.length > 1 && args[1] != null && !args[1].trim().isEmpty()) {
			System.setProperty("tradingbot.futuresDataDir", args[1].trim());
		}
		FuturesManager.initializeStore();
		int id = FuturesManager.generatePortfolioBacktest(
			"MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL",
			"2025-05-01",
			"2026-05-22",
			50000.0,
			100000.0,
			100000.0,
			700.0,
			50,
			1.24,
			1.0,
			3,
			50,
			5.0,
			false,
			0.0,
			"CUSTOM",
			"biasfree94k",
			0,
			true
		);
		System.out.println("RUN_ID=" + id);
	}
}
