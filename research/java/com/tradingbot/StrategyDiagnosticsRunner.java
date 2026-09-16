package com.tradingbot;

public class StrategyDiagnosticsRunner {
	private static final String START_DATE = "2025-05-01";
	private static final String END_DATE = "2026-05-04";

	public static void main(String[] args) throws Exception {
		String symbol = args.length > 0 ? args[0].trim().toUpperCase() : "MNQ";
		String mode = args.length > 1 ? args[1].trim().toLowerCase() : "rcb";
		FuturesManager.FuturesStrategySettings original = FuturesManager.loadFuturesStrategySettings(symbol);
		try {
			FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol);
			applyBaseline(symbol, settings);
			if ("rcb".equals(mode)) {
				configureRangeCompression(settings);
			} else if ("vpb".equals(mode)) {
				configureValueArea(settings);
			} else if ("loose-rcb".equals(mode)) {
				configureLooseRangeCompression(settings);
			} else if ("loose-vpb".equals(mode)) {
				configureLooseValueArea(settings);
			}
			FuturesManager.saveFuturesStrategySettings(symbol, settings);
			System.out.println(FuturesManager.getStrategyDiagnosticsJson(
				symbol,
				START_DATE,
				END_DATE,
				50000.0,
				2000.0,
				1000.0,
				700.0,
				50,
				1.24,
				1.0,
				true
			));
		} finally {
			FuturesManager.saveFuturesStrategySettings(symbol, original);
		}
	}

	private static void applyBaseline(String symbol, FuturesManager.FuturesStrategySettings settings) {
		if ("MNQ".equals(symbol)) {
			settings.sweep.enabled = true;
			settings.sweep.maxTradesPerDay = 5;
			settings.enableLateSweep = true;
			settings.enableSweepSecondChance = true;
			settings.earlySweepReclaimTicks = 4.0;
			settings.lateSweepReclaimTicks = 10.0;
			settings.lateSweepCloseLocation = 0.35;
			settings.keltnerReversion.enabled = true;
			settings.keltnerReversion.maxTradesPerDay = 6;
			settings.keltnerMaxRiskTicks = 22.0;
			settings.keltnerRewardRisk = 0.85;
			settings.openingMomentumBucketMinutes = 10;
			settings.openingMomentum.maxTradesPerDay = 10;
			settings.openingMomentumVolumeRatio = 0.50;
			settings.openingMomentumLongVolumeRatio = 0.0;
			settings.openingMomentumShortVolumeRatio = 0.0;
			settings.openingMomentumRewardRisk = 0.55;
		}
		if ("NQ".equals(symbol)) {
			settings.priorDayBreakout.enabled = true;
			settings.priorDayBreakout.maxTradesPerDay = 5;
			settings.allowPriorDayBreakoutLongs = false;
			settings.allowPriorDayBreakoutShorts = true;
			settings.priorDayBreakoutMinVolumeRatio = 0.75;
			settings.keltnerReversion.enabled = true;
			settings.keltnerReversion.maxTradesPerDay = 8;
			settings.keltnerMaxRiskTicks = 22.0;
			settings.keltnerRewardRisk = 0.85;
			settings.keltnerAtrMultiplier = 1.45;
			settings.keltnerMinVolumeRatio = 0.65;
			settings.keltnerMinBodyPct = 16.0;
			settings.keltnerMinBandWidthTicks = 8.0;
			settings.keltnerBucketMinutes = 15;
		}
		if ("MGC".equals(symbol)) {
			settings.sweep.enabled = true;
			settings.sweep.maxTradesPerDay = 5;
			settings.orb.enabled = true;
			settings.orb.maxTradesPerDay = 3;
		}
	}

	private static void configureRangeCompression(FuturesManager.FuturesStrategySettings settings) {
		settings.rangeCompressionBreakout.enabled = true;
		settings.rangeCompressionBreakout.maxTradesPerDay = 4;
		settings.allowRangeCompressionLongs = true;
		settings.allowRangeCompressionShorts = true;
		settings.rangeCompressionStartMinute = 600;
		settings.rangeCompressionEndMinute = 915;
		settings.rangeCompressionBars = 5;
		settings.rangeCompressionBucketMinutes = 12;
		settings.rangeCompressionMaxAtrRatio = 0.70;
		settings.rangeCompressionMinVolumeRatio = 0.65;
		settings.rangeCompressionMaxRiskTicks = 22.0;
		settings.rangeCompressionRewardRisk = 0.65;
		settings.rangeCompressionMaxDistanceTicks = 52.0;
		settings.rangeCompressionMinBodyPct = 14.0;
		settings.rangeCompressionMinTrendSlopeTicks = 0.20;
		settings.rangeCompressionMaxHoldBars = 10;
		settings.requireHigherTimeframeGuard = false;
	}

	private static void configureLooseRangeCompression(FuturesManager.FuturesStrategySettings settings) {
		configureRangeCompression(settings);
		settings.rangeCompressionBreakout.maxTradesPerDay = 8;
		settings.rangeCompressionStartMinute = 575;
		settings.rangeCompressionBars = 3;
		settings.rangeCompressionBucketMinutes = 6;
		settings.rangeCompressionMaxAtrRatio = 1.10;
		settings.rangeCompressionMinVolumeRatio = 0.0;
		settings.rangeCompressionMaxRiskTicks = 36.0;
		settings.rangeCompressionRewardRisk = 0.50;
		settings.rangeCompressionMaxDistanceTicks = 120.0;
		settings.rangeCompressionMinBodyPct = 0.0;
		settings.rangeCompressionMinTrendSlopeTicks = 0.0;
	}

	private static void configureValueArea(FuturesManager.FuturesStrategySettings settings) {
		settings.valueAreaReclaim.enabled = true;
		settings.valueAreaReclaim.maxTradesPerDay = 3;
		settings.allowValueAreaLongs = true;
		settings.allowValueAreaShorts = true;
		settings.valueAreaStartMinute = 585;
		settings.valueAreaEndMinute = 900;
		settings.valueAreaBucketMinutes = 45;
		settings.valueAreaPct = 0.70;
		settings.valueAreaBinTicks = 4.0;
		settings.valueAreaReclaimTicks = 3.0;
		settings.valueAreaMinVolumeRatio = 0.65;
		settings.valueAreaMaxRiskTicks = 36.0;
		settings.valueAreaRewardRisk = 0.85;
		settings.valueAreaMaxHoldBars = 30;
		settings.rangeCompressionMinTrendSlopeTicks = 0.10;
		settings.requireHigherTimeframeGuard = false;
	}

	private static void configureLooseValueArea(FuturesManager.FuturesStrategySettings settings) {
		configureValueArea(settings);
		settings.valueAreaReclaim.maxTradesPerDay = 8;
		settings.valueAreaStartMinute = 570;
		settings.valueAreaEndMinute = 930;
		settings.valueAreaBucketMinutes = 10;
		settings.valueAreaReclaimTicks = 1.0;
		settings.valueAreaMinVolumeRatio = 0.0;
		settings.valueAreaMaxRiskTicks = 80.0;
		settings.valueAreaRewardRisk = 0.50;
		settings.rangeCompressionMinTrendSlopeTicks = 0.0;
	}
}
