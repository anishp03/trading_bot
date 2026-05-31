package com.tradingbot;

public class LiquidityReclaimSyntheticBacktestRunner {
	public static void main(String[] args) {
		if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
			System.setProperty("tradingbot.db.path", args[0].trim());
		}
		if (args.length > 1 && args[1] != null && !args[1].trim().isEmpty()) {
			System.setProperty("tradingbot.futuresDataDir", args[1].trim());
		}
		FuturesManager.initializeStore();
		int baselineId = FuturesManager.generatePortfolioBacktest(
			"MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL",
			"2025-05-23",
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
			"bestbiasfree",
			0,
			true
		);
		int syntheticId = FuturesManager.generateSyntheticLiveOnlyLiquidityReclaimBacktest(
			"MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL",
			"2025-05-23",
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
			"bestbiasfree",
			0,
			true
		);
		System.out.println("BASELINE_RUN_ID=" + baselineId);
		System.out.println("SYNTHETIC_LIQUIDITY_RECLAIM_RUN_ID=" + syntheticId);
	}
}
