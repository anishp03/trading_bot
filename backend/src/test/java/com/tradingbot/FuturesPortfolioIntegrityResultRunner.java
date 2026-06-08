package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class FuturesPortfolioIntegrityResultRunner {
	@Test
	public void generateCorrectedPortfolioBacktest() {
		String symbols = property("integrity.symbols", "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL");
		String startDate = property("integrity.startDate", "2025-05-01");
		String endDate = property("integrity.endDate", "2026-06-06");
		double accountSize = doubleProperty("integrity.accountSize", 50000.0);
		double maxTrailingDrawdown = doubleProperty("integrity.maxTrailingDrawdown", 2000.0);
		double dailyLossLimit = doubleProperty("integrity.dailyLossLimit", 1000.0);
		double maxRiskPerTrade = doubleProperty("integrity.maxRiskPerTrade", 700.0);
		int maxContracts = intProperty("integrity.maxContracts", 50);
		double commissionPerContract = doubleProperty("integrity.commissionPerContract", 1.24);
		double slippageTicks = doubleProperty("integrity.slippageTicks", 1.0);
		int maxOpenPositions = intProperty("integrity.maxOpenPositions", 3);
		int maxAggregateContracts = intProperty("integrity.maxAggregateContracts", 50);
		double maxAggregateMiniUnits = doubleProperty("integrity.maxAggregateMiniUnits", 5.0);
		boolean useSavedRisk = booleanProperty("integrity.useSavedRisk", true);
		double profitTarget = doubleProperty("integrity.profitTarget", 0.0);
		String fundedProfile = property("integrity.fundedProfile", "TOPSTEP_50K");
		String strategyPreset = property("integrity.strategyPreset", "bestbiasfree");
		int sourcePortfolioBacktestId = intProperty("integrity.sourcePortfolioBacktestId", 1);
		boolean continueAfterRuleViolation = booleanProperty("integrity.continueAfterRuleViolation", true);
		boolean qualitativeRiskEnabled = booleanProperty("integrity.qualitativeRiskEnabled", true);
		boolean dtmEnabled = booleanProperty("integrity.dtmEnabled", true);

		int backtestId = FuturesManager.generatePortfolioBacktest(
			symbols,
			startDate,
			endDate,
			accountSize,
			maxTrailingDrawdown,
			dailyLossLimit,
			maxRiskPerTrade,
			maxContracts,
			commissionPerContract,
			slippageTicks,
			maxOpenPositions,
			maxAggregateContracts,
			maxAggregateMiniUnits,
			useSavedRisk,
			profitTarget,
			fundedProfile,
			strategyPreset,
			sourcePortfolioBacktestId,
			continueAfterRuleViolation,
			qualitativeRiskEnabled,
			dtmEnabled
		);

		System.out.println("INTEGRITY_BACKTEST_ID=" + backtestId);
		System.out.println("INTEGRITY_BACKTESTS_JSON=" + FuturesManager.getPortfolioBacktestsJson());
		assertTrue(backtestId > 0, "Portfolio backtest should be generated");
	}

	private static String property(String key, String fallback) {
		String value = System.getProperty(key);
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	private static int intProperty(String key, int fallback) {
		try {
			return Integer.parseInt(property(key, String.valueOf(fallback)));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static double doubleProperty(String key, double fallback) {
		try {
			return Double.parseDouble(property(key, String.valueOf(fallback)));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean booleanProperty(String key, boolean fallback) {
		String value = property(key, String.valueOf(fallback));
		return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
	}
}
