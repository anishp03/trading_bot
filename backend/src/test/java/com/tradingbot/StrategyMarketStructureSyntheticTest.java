package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StrategyMarketStructureSyntheticTest {
	private static final LocalDate NORMAL_DAY = LocalDate.of(2026, 5, 26);
	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 5, 27);

	@Test
	public void syntheticValidAndInvalidStructuresExerciseAllStrategyEngines() throws Exception {
		for (String code : directStrategyCodes()) {
			Scenario valid = validScenario(code);
			assertTrue(hasSignalCode(invokeBuildSignals(valid), code), code + " should approve its synthetic valid market structure");
			Scenario invalid = invalidScenario(code);
			assertFalse(hasSignalCode(invokeBuildSignals(invalid), code), code + " should reject a flat invalid market structure");
		}

		assertTrue(sourceStrategyEventCreated("SHDW"), "SHDW should approve a valid source event plus target confirmation");
		assertFalse(sourceStrategyEventCreatedWithoutConfirmation("SHDW"), "SHDW should reject when target confirmation is missing");
		assertTrue(sourceStrategyEventCreated("ECHO"), "ECHO should approve a valid delayed echo source plus target confirmation");
		assertFalse(sourceStrategyEventCreatedWithoutConfirmation("ECHO"), "ECHO should reject when target confirmation is missing");
		assertTrue(sourceStrategyEventCreated("WFT"), "WFT should approve a valid target-hit follow-through source plus confirmation");
		assertFalse(sourceStrategyEventCreatedWithoutConfirmation("WFT"), "WFT should reject when target confirmation is missing");
		assertTrue(sourceStrategyEventCreated("MYMBR"), "MYMBR should approve a valid MYM breadth-fade structure");
		assertFalse(sourceStrategyEventCreatedWithoutConfirmation("MYMBR"), "MYMBR should reject without aligned breadth markets");
	}

	@Test
	public void orbRetestAllowsSameCandleBreakoutRetestAgain() throws Exception {
		assertTrue(hasSignalCode(invokeBuildSignals(validScenario("ORB2")), "ORB2"), "ORB2 should accept a later retest after an earlier breakout");
		assertTrue(hasSignalCode(invokeBuildSignals(sameCandleOrb2Scenario()), "ORB2"), "ORB2 should accept the same-candle breakout/retest entries restored for this sprint");
	}

	@Test
	public void fvgRejectsContinuationAfterGapBodyCloseInvalidation() throws Exception {
		assertFalse(hasSignalCode(invokeBuildSignals(invalidatedFvgScenario()), "FVG"), "FVG continuation should reject after the gap closes through and inverts");
	}

	@Test
	public void rangeMidpointContinuationRequiresConfirmingOrderFlow() throws Exception {
		Scenario scenario = validRmcScenario();
		Map<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>> orderFlow = orderFlowMap(
			scenario.symbol,
			NORMAL_DAY,
			LocalTime.of(11, 5),
			-0.34,
			-180.0,
			"ASK_FLIP",
			"ASK_ABSORPTION"
		);

		assertTrue(hasSignalCode(invokeBuildSignals(scenario, orderFlow), "RMC"), "RMC should accept a midpoint rejection only when Level 2 confirms continuation.");
	}

	@Test
	public void rangeMidpointContinuationRejectsOpposingOrderFlow() throws Exception {
		Scenario scenario = validRmcScenario();
		Map<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>> orderFlow = orderFlowMap(
			scenario.symbol,
			NORMAL_DAY,
			LocalTime.of(11, 5),
			0.42,
			220.0,
			"BID_FLIP",
			"BID_ABSORPTION"
		);

		assertFalse(hasSignalCode(invokeBuildSignals(scenario, orderFlow), "RMC"), "RMC should reject candle-only midpoint continuation when Level 2 argues against the continuation.");
	}

	@Test
	public void rangeMidpointContinuationRejectsViolentCountertrendPullback() throws Exception {
		Scenario scenario = validRmcScenario();
		for (int index = 90; index <= 94; index++) {
			set(scenario.bars.get(index), "volume", 4200.0);
		}
		Map<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>> orderFlow = orderFlowMap(
			scenario.symbol,
			NORMAL_DAY,
			LocalTime.of(11, 5),
			-0.34,
			-180.0,
			"ASK_FLIP",
			"ASK_ABSORPTION"
		);

		assertFalse(hasSignalCode(invokeBuildSignals(scenario, orderFlow), "RMC"), "RMC should reject midpoint continuations when the pullback shows heavy countertrend participation.");
	}

	@Test
	public void rangeMidpointContinuationRejectsOpposingFifteenMinuteContext() throws Exception {
		Scenario scenario = validRmcScenario();
		Map<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>> orderFlow = orderFlowMap(
			scenario.symbol,
			NORMAL_DAY,
			LocalTime.of(11, 5),
			-0.34,
			-180.0,
			"ASK_FLIP",
			"ASK_ABSORPTION"
		);
		List<Object> fifteenMinuteBars = flatBars(scenario.symbol, NORMAL_DAY, LocalTime.of(10, 30), 3, basePrice(scenario.symbol));
		double base = basePrice(scenario.symbol);
		double tick = tickSize(scenario.symbol);
		setCandle(fifteenMinuteBars, 1, base + (18 * tick), base + (24 * tick), base + (17 * tick), base + (23 * tick), Trend.LONG, scenario.symbol);
		setCandle(fifteenMinuteBars, 2, base + (22 * tick), base + (28 * tick), base + (21 * tick), base + (26 * tick), Trend.LONG, scenario.symbol);

		assertFalse(hasSignalCode(invokeBuildSignals(scenario, orderFlow, fifteenMinuteBars), "RMC"), "RMC should reject a Level 2-confirmed midpoint short when the latest closed 15-minute auction is bullish.");
	}

	private static List<String> directStrategyCodes() {
		return Arrays.asList(
			"ORB", "ORB2", "LORB", "OMOM", "SWEEP", "SWEEP2", "PDB", "VWAP", "VRCL", "MRVWAP",
			"FVG", "CMOM", "AFT", "MIM", "IPB", "KELT", "KREV", "MSCALP", "TLAD", "RCB",
			"VPB", "EIA", "COPEN", "IDXCONF", "MYMORB2", "MCLTC"
		);
	}

	private static Scenario invalidScenario(String code) throws Exception {
		String symbol = "MCL".equals(symbolFor(code)) ? "MCL" : symbolFor(code);
		LocalTime start = "COPEN".equals(code) ? LocalTime.of(9, 0) : LocalTime.of(9, 30);
		LocalDate day = "EIA".equals(code) ? WEDNESDAY : NORMAL_DAY;
		return new Scenario(symbol, flatBars(symbol, day, start, 420, basePrice(symbol)), previousBars(symbol), settingsFor(code));
	}

	private static Scenario validScenario(String code) throws Exception {
		if ("COPEN".equals(code)) {
			return validCrudeOpenScenario();
		}
		String symbol = symbolFor(code);
		LocalDate day = "EIA".equals(code) ? WEDNESDAY : NORMAL_DAY;
		List<Object> bars = flatBars(symbol, day, LocalTime.of(9, 30), 420, basePrice(symbol));
		FuturesManager.FuturesStrategySettings settings = settingsFor(code);
		double tick = tickSize(symbol);
		double base = basePrice(symbol);
		if ("ORB".equals(code)) {
			openingRange(bars, base + (4 * tick), base - (4 * tick));
			setCandle(bars, 15, base + (7 * tick), base + (10 * tick), base + (6 * tick), base + (8.6 * tick), Trend.LONG, symbol);
		} else if ("ORB2".equals(code)) {
			openingRange(bars, base + (4 * tick), base - (4 * tick));
			setCandle(bars, 15, base + (7 * tick), base + (10 * tick), base + (6 * tick), base + (9 * tick), Trend.LONG, symbol);
			setCandle(bars, 20, base + (3 * tick), base + (7 * tick), base + (2 * tick), base + (6 * tick), Trend.LONG, symbol);
		} else if ("LORB".equals(code)) {
			openingRange(bars, base + (4 * tick), base - (4 * tick));
			setCandle(bars, 100, base + (7 * tick), base + (10 * tick), base + (6 * tick), base + (8.6 * tick), Trend.LONG, symbol);
		} else if ("OMOM".equals(code)) {
			openingRange(bars, base + (4 * tick), base - (4 * tick), 10);
			setCandle(bars, 10, base + (7 * tick), base + (10 * tick), base + (6 * tick), base + (8.6 * tick), Trend.LONG, symbol);
		} else if ("VWAP".equals(code)) {
			setPullbackThenBreak(bars, 30, base, Trend.LONG, symbol);
		} else if ("VRCL".equals(code)) {
			setVwapTouchThenReclaim(bars, 40, base, Trend.LONG, symbol);
		} else if ("MSCALP".equals(code)) {
			setPullbackThenBreak(bars, 35, base, Trend.LONG, symbol);
		} else if ("TLAD".equals(code)) {
			setPullbackThenBreak(bars, 65, base, Trend.LONG, symbol);
		} else if ("RCB".equals(code)) {
			setCompressionBreakout(bars, 70, base, Trend.LONG, symbol);
		} else if ("VPB".equals(code)) {
			setCandle(bars, 30, base + (3 * tick), base + (8 * tick), base + tick, base + (6 * tick), Trend.LONG, symbol);
		} else if ("KELT".equals(code)) {
			setKeltnerBreakout(bars, 65, base, Trend.LONG, symbol);
		} else if ("KREV".equals(code)) {
			setKeltnerReclaim(bars, 65, base, Trend.LONG, symbol);
		} else if ("MRVWAP".equals(code)) {
			setMeanReversion(bars, 120, base, Trend.LONG, symbol);
		} else if ("AFT".equals(code)) {
			setChannelBreakout(bars, 230, base, Trend.LONG, symbol);
		} else if ("IPB".equals(code)) {
			setOpeningImpulse(bars, base, symbol);
			setPullbackThenBreak(bars, 105, base + (24 * tick), Trend.LONG, symbol);
		} else if ("MIM".equals(code)) {
			setOpeningImpulse(bars, base, symbol);
			setCandle(bars, 300, base + (20 * tick), base + (22 * tick), base + (19 * tick), base + (21 * tick), Trend.LONG, symbol);
			setCandle(bars, 355, base + (26 * tick), base + (30 * tick), base + (25 * tick), base + (29 * tick), Trend.LONG, symbol);
		} else if ("SWEEP".equals(code)) {
			setCandle(bars, 260, base - (2 * tick), base + (2 * tick), base - (8 * tick), base + (1 * tick), Trend.LONG, symbol);
		} else if ("SWEEP2".equals(code)) {
			setCandle(bars, 220, base - (2 * tick), base + (2 * tick), base - (8 * tick), base + (1 * tick), Trend.LONG, symbol);
			setCandle(bars, 221, base + (2 * tick), base + (6 * tick), base, base + (5 * tick), Trend.LONG, symbol);
		} else if ("PDB".equals(code)) {
			setCandle(bars, 40, base + (6 * tick), base + (9 * tick), base + (5 * tick), base + (7 * tick), Trend.LONG, symbol);
			setCandle(bars, 50, base + (4 * tick), base + (8 * tick), base + tick, base + (6 * tick), Trend.LONG, symbol);
		} else if ("FVG".equals(code)) {
			setFvgReclaim(bars, 90, base, symbol);
		} else if ("CMOM".equals(code)) {
			setCandle(bars, 305, base + (20 * tick), base + (24 * tick), base + (19 * tick), base + (23 * tick), Trend.LONG, symbol);
		} else if ("EIA".equals(code)) {
			setMclInventoryRangeAndBreakout(bars, base, symbol);
		} else if ("IDXCONF".equals(code)) {
			setChannelBreakout(bars, 60, base, Trend.LONG, symbol);
		} else if ("MYMORB2".equals(code)) {
			openingRange(bars, base + (4 * tick), base - (4 * tick));
			setCandle(bars, 15, base + (7 * tick), base + (10 * tick), base + (6 * tick), base + (9 * tick), Trend.LONG, symbol);
			setCandle(bars, 30, base + (3 * tick), base + (7 * tick), base + (2 * tick), base + (6 * tick), Trend.LONG, symbol);
		} else if ("MCLTC".equals(code)) {
			setChannelBreakout(bars, 90, base, Trend.LONG, symbol);
		}
		return new Scenario(symbol, bars, previousBars(symbol), settings);
	}

	private static Scenario validRmcScenario() throws Exception {
		String symbol = "MNQ";
		double base = basePrice(symbol);
		double tick = tickSize(symbol);
		List<Object> bars = flatBars(symbol, NORMAL_DAY, LocalTime.of(9, 30), 420, base);
		for (int index = 45; index <= 82; index++) {
			setCandle(bars, index, base - (index - 44) * tick, base - (index - 43) * tick, base - (index - 40) * tick, base - (index - 42) * tick, Trend.SHORT, symbol);
		}
		setCandle(bars, 90, base - (42 * tick), base - (36 * tick), base - (46 * tick), base - (38 * tick), Trend.LONG, symbol);
		setCandle(bars, 91, base - (38 * tick), base - (31 * tick), base - (42 * tick), base - (33 * tick), Trend.LONG, symbol);
		setCandle(bars, 92, base - (33 * tick), base - (26 * tick), base - (38 * tick), base - (28 * tick), Trend.LONG, symbol);
		setCandle(bars, 93, base - (28 * tick), base - (22 * tick), base - (34 * tick), base - (24 * tick), Trend.LONG, symbol);
		setCandle(bars, 94, base - (24 * tick), base - (18 * tick), base - (30 * tick), base - (22 * tick), Trend.LONG, symbol);
		setCandle(bars, 95, base - (22 * tick), base - (18 * tick), base - (38 * tick), base - (31 * tick), Trend.SHORT, symbol);
		FuturesManager.FuturesStrategySettings settings = settingsFor("RMC");
		return new Scenario(symbol, bars, previousBars(symbol), settings);
	}

	private static Scenario sameCandleOrb2Scenario() throws Exception {
		String symbol = "MES";
		List<Object> bars = flatBars(symbol, NORMAL_DAY, LocalTime.of(9, 30), 420, basePrice(symbol));
		FuturesManager.FuturesStrategySettings settings = settingsFor("ORB2");
		double tick = tickSize(symbol);
		double base = basePrice(symbol);
		openingRange(bars, base + (4 * tick), base - (4 * tick));
		setCandle(bars, 15, base + (5.2 * tick), base + (6.8 * tick), base + (4.5 * tick), base + (6.4 * tick), Trend.LONG, symbol);
		return new Scenario(symbol, bars, previousBars(symbol), settings);
	}

	private static Scenario invalidatedFvgScenario() throws Exception {
		String symbol = "NQ";
		List<Object> bars = flatBars(symbol, NORMAL_DAY, LocalTime.of(9, 30), 420, basePrice(symbol));
		FuturesManager.FuturesStrategySettings settings = settingsFor("FVG");
		double tick = tickSize(symbol);
		double base = basePrice(symbol);
		setFvgReclaim(bars, 90, base, symbol);
		setCandle(bars, 91, base + (2 * tick), base + (7 * tick), base - (2 * tick), base - tick, Trend.SHORT, symbol);
		setCandle(bars, 92, base + (6 * tick), base + (9 * tick), base + (5 * tick), base + (8 * tick), Trend.LONG, symbol);
		return new Scenario(symbol, bars, previousBars(symbol), settings);
	}

	private static FuturesManager.FuturesStrategySettings settingsFor(String code) throws Exception {
		FuturesManager.FuturesStrategySettings settings = new FuturesManager.FuturesStrategySettings();
		disableAllToggles(settings);
		settings.requireHigherTimeframeGuard = false;
		settings.vwapRequireHigherTimeframeGuard = false;
		settings.fvgRequireCoreQuality = false;
		settings.fvgRequireEmaStack = false;
		settings.fvgRequireHigherTimeframeGuard = false;
		settings.relaxPatternHardWindows = true;
		settings.allowShorts = true;
		settings.maxInitialRiskTicks = 500.0;
		settings.orbMaxSignalRangeTicks = 500.0;
		settings.orbMaxBreakoutExtensionTicks = 500.0;
		settings.orbMaxVwapDistanceTicks = 500.0;
		settings.orbMaxTrendSlopeTicks = 500.0;
		settings.orbShortConfirmationMinute = 0;
		settings.orbRetestMaxRiskTicks = 500.0;
		settings.orbRetestAllowedSymbols = "";
		settings.lateOrbContinuationStartMinute = 600;
		settings.lateOrbContinuationEndMinute = 900;
		settings.lateOrbContinuationMinVolumeRatio = 0.0;
		settings.lateOrbContinuationMaxRiskTicks = 500.0;
		settings.allowLateOrbContinuationLongs = true;
		settings.openingMomentumVolumeRatio = 0.0;
		settings.openingMomentumMaxRiskTicks = 500.0;
		settings.openingMomentumLongStartMinute = 570;
		settings.openingMomentumLongEndMinute = 900;
		settings.openingMomentumShortStartMinute = 570;
		settings.openingMomentumShortEndMinute = 900;
		settings.vwapMinVolumeRatio = 0.0;
		settings.vwapMinTrendSlopeTicks = 0.0;
		settings.vwapMaxDistanceTicks = 500.0;
		settings.vwapMaxRiskTicks = 500.0;
		settings.vwapReclaimMinVolumeRatio = 0.0;
		settings.vwapReclaimMaxRiskTicks = 500.0;
		settings.fvgMinVolumeRatio = 0.0;
		settings.fvgMinRiskTicks = 1.0;
		settings.fvgMaxRiskTicks = 500.0;
		settings.meanReversionMinDistanceTicks = 1.0;
		settings.meanReversionOversoldRsi = 101.0;
		settings.meanReversionOverboughtRsi = -1.0;
		settings.closeMomentumMinMoveTicks = 1.0;
		settings.closeMomentumVolumeRatio = 0.0;
		settings.afternoonMinVolumeRatio = 0.0;
		settings.afternoonMaxRiskTicks = 500.0;
		settings.afternoonStartMinute = 570;
		settings.afternoonEndMinute = 930;
		settings.marketIntradayMomentumMinOpenMoveTicks = 4.0;
		settings.marketIntradayMomentumMinLateMoveTicks = 2.0;
		settings.marketIntradayMomentumMinVolumeRatio = 0.0;
		settings.marketIntradayMomentumMaxRiskTicks = 500.0;
		settings.marketImpulsePullbackStartMinute = 570;
		settings.marketImpulsePullbackEndMinute = 930;
		settings.keltnerMaxRiskTicks = 500.0;
		settings.keltnerMinBandWidthTicks = 1.0;
		settings.keltnerMinVolumeRatio = 0.0;
		settings.keltnerMinBodyPct = 0.0;
		settings.keltnerMinTrendSlopeTicks = 0.0;
		settings.keltnerAtrMultiplier = 1.0;
		settings.microScalpMinVolumeRatio = 0.0;
		settings.microScalpMaxRiskTicks = 500.0;
		settings.microScalpMinBodyPct = 0.0;
		settings.microScalpMinTrendSlopeTicks = 0.0;
		settings.trendLadderMinVolumeRatio = 0.0;
		settings.trendLadderMaxRiskTicks = 500.0;
		settings.trendLadderMinTrendSlopeTicks = 0.0;
		settings.trendLadderPullbackTicks = 12.0;
		settings.rangeCompressionMinVolumeRatio = 0.0;
		settings.rangeCompressionMaxRiskTicks = 500.0;
		settings.rangeCompressionMaxDistanceTicks = 500.0;
		settings.rangeCompressionMinBodyPct = 0.0;
		settings.rangeCompressionMinTrendSlopeTicks = 0.0;
		settings.valueAreaMinVolumeRatio = 0.0;
		settings.valueAreaMaxRiskTicks = 500.0;
		settings.valueAreaReclaimTicks = 1.0;
		settings.valueAreaBinTicks = 1.0;
		settings.priorDayBreakoutMinBreakTicks = 1.0;
		settings.priorDayBreakoutMaxRiskTicks = 500.0;
		settings.priorDayBreakoutMinVolumeRatio = 0.0;
		settings.priorDayBreakoutStartMinute = 570;
		settings.priorDayBreakoutEndMinute = 930;
		settings.sweepCloseLocation = 0.0;
		settings.lateSweepCloseLocation = 0.0;
		settings.earlySweepReclaimTicks = 0.0;
		settings.lateSweepReclaimTicks = 0.0;
		settings.minBodyPct = 0.0;
		settings.mclEiaMinVolumeRatio = 0.0;
		settings.mclEiaMinBodyPct = 0.0;
		settings.mclCrudeOpenMinVolumeRatio = 0.0;
		settings.mclCrudeOpenMinBodyPct = 0.0;
		settings.mymIndexConfirmationMaxRiskTicks = 500.0;
		settings.mymIndexConfirmationMinVolumeRatio = 0.0;
		settings.mymIndexConfirmationMinBodyPct = 0.0;
		settings.mymIndexConfirmationMinTrendSlopeTicks = 0.0;
		settings.mymOrbRetestMaxRiskTicks = 500.0;
		settings.mymOrbRetestMinVolumeRatio = 0.0;
		settings.mymOrbRetestMinBodyPct = 0.0;
		settings.mclTrendMaxRiskTicks = 500.0;
		settings.mclTrendMinOpenMoveTicks = 1.0;
		settings.mclTrendMinVolumeRatio = 0.0;
		settings.mclTrendMinBodyPct = 0.0;
		settings.mclTrendMinTrendSlopeTicks = 0.0;

		if ("ORB".equals(code) || "ORB2".equals(code)) {
			setToggle(settings, "orb", true, 5);
			settings.enableOrbRetest = "ORB2".equals(code);
		} else if ("LORB".equals(code)) setToggle(settings, "lateOrbContinuation", true, 5);
		else if ("OMOM".equals(code)) setToggle(settings, "openingMomentum", true, 5);
		else if ("SWEEP".equals(code) || "SWEEP2".equals(code)) setToggle(settings, "sweep", true, 5);
		else if ("PDB".equals(code)) setToggle(settings, "priorDayBreakout", true, 5);
		else if ("VWAP".equals(code)) setToggle(settings, "vwapPullback", true, 5);
		else if ("VRCL".equals(code)) setToggle(settings, "vwapReclaim", true, 5);
		else if ("MRVWAP".equals(code)) setToggle(settings, "vwapMeanReversion", true, 5);
		else if ("FVG".equals(code)) setToggle(settings, "fvg", true, 5);
		else if ("CMOM".equals(code)) setToggle(settings, "closeMomentum", true, 5);
		else if ("AFT".equals(code)) setToggle(settings, "afternoonContinuation", true, 5);
		else if ("MIM".equals(code) || "IPB".equals(code)) setToggle(settings, "marketIntradayMomentum", true, "IPB".equals(code) ? 3 : 1);
		else if ("KELT".equals(code)) setToggle(settings, "keltnerScalp", true, 5);
		else if ("KREV".equals(code)) setToggle(settings, "keltnerReversion", true, 5);
		else if ("MSCALP".equals(code)) setToggle(settings, "microScalp", true, 5);
		else if ("TLAD".equals(code)) setToggle(settings, "trendLadder", true, 5);
		else if ("RCB".equals(code)) setToggle(settings, "rangeCompressionBreakout", true, 5);
		else if ("RMC".equals(code)) setToggle(settings, "rangeMidpointContinuation", true, 5);
		else if ("VPB".equals(code)) setToggle(settings, "valueAreaReclaim", true, 5);
		else if ("EIA".equals(code)) setToggle(settings, "mclEiaContinuation", true, 5);
		else if ("COPEN".equals(code)) setToggle(settings, "mclCrudeSessionOpen", true, 5);
		else if ("IDXCONF".equals(code)) setToggle(settings, "mymIndexConfirmation", true, 5);
		else if ("MYMORB2".equals(code)) setToggle(settings, "mymOrbRetest", true, 5);
		else if ("MCLTC".equals(code)) setToggle(settings, "mclTrendContinuation", true, 5);
		return settings;
	}

	private static boolean sourceStrategyEventCreated(String code) throws Exception {
		if ("SHDW".equals(code)) {
			return microShadowEvent(true);
		}
		if ("ECHO".equals(code)) {
			return microEchoEvent(true);
		}
		if ("WFT".equals(code)) {
			return winnerFollowThroughEvent(true);
		}
		if ("MYMBR".equals(code)) {
			return mymBreadthEvent(true);
		}
		return false;
	}

	private static boolean sourceStrategyEventCreatedWithoutConfirmation(String code) throws Exception {
		if ("SHDW".equals(code)) {
			return microShadowEvent(false);
		}
		if ("ECHO".equals(code)) {
			return microEchoEvent(false);
		}
		if ("WFT".equals(code)) {
			return winnerFollowThroughEvent(false);
		}
		if ("MYMBR".equals(code)) {
			return mymBreadthEvent(false);
		}
		return true;
	}

	private static boolean microShadowEvent(boolean confirming) throws Exception {
		FuturesManager.FuturesStrategySettings settings = settingsForSourceStrategies();
		setToggle(settings, "microShadow", true, 5);
		List<Object> sourceBars = flatBars("ES", NORMAL_DAY, LocalTime.of(9, 30), 120, basePrice("ES"));
		List<Object> targetBars = flatBars("MES", NORMAL_DAY, LocalTime.of(9, 30), 120, basePrice("MES"));
		if (confirming) setPullbackThenBreak(targetBars, 59, basePrice("MES"), Trend.LONG, "MES");
		Object sourceContext = context("ES", sourceBars, settings);
		Object targetContext = context("MES", targetBars, settings);
		addEvent(sourceContext, NORMAL_DAY, sourceSignal("VRCL", "VWAP Reclaim", "LONG", 59, 60, basePrice("ES")));
		Map<String, Object> contexts = new HashMap<String, Object>();
		contexts.put("ES", sourceContext);
		contexts.put("MES", targetContext);
		invokePrivate("addMicroShadowSignalPair", new Class<?>[] { Map.class, String.class, String.class }, contexts, "ES", "MES");
		return contextHasEventCode(targetContext, "SHDW");
	}

	private static boolean microEchoEvent(boolean confirming) throws Exception {
		FuturesManager.FuturesStrategySettings settings = settingsForSourceStrategies();
		setToggle(settings, "microEcho", true, 5);
		settings.microEchoDelayMinutes = 3;
		settings.microEchoMaxDelays = 1;
		List<Object> bars = flatBars("MES", NORMAL_DAY, LocalTime.of(9, 30), 120, basePrice("MES"));
		if (confirming) setPullbackThenBreak(bars, 32, basePrice("MES"), Trend.LONG, "MES");
		Object sourceContext = context("MES", bars, settings);
		addEvent(sourceContext, NORMAL_DAY, sourceSignal("VRCL", "VWAP Reclaim", "LONG", 29, 30, basePrice("MES")));
		Map<String, Object> contexts = new HashMap<String, Object>();
		contexts.put("MES", sourceContext);
		invokePrivate("addMicroEchoSignalPair", new Class<?>[] { Map.class, String.class, String.class }, contexts, "MES", "MES");
		return contextHasEventCode(sourceContext, "ECHO");
	}

	private static boolean winnerFollowThroughEvent(boolean confirming) throws Exception {
		FuturesManager.FuturesStrategySettings settings = settingsForSourceStrategies();
		setToggle(settings, "winnerFollowThrough", true, 5);
		List<Object> bars = flatBars("MES", NORMAL_DAY, LocalTime.of(9, 30), 120, basePrice("MES"));
		if (confirming) setPullbackThenBreak(bars, 60, basePrice("MES"), Trend.LONG, "MES");
		Object context = context("MES", bars, settings);
		Map<String, Object> contexts = new HashMap<String, Object>();
		contexts.put("MES", context);
		Object trade = newPrivate("com.tradingbot.FuturesManager$FuturesTrade");
		set(trade, "symbol", "MES");
		set(trade, "strategyCode", "OMOM");
		set(trade, "strategyName", "Opening Momentum");
		set(trade, "side", "LONG");
		set(trade, "pnl", 100.0);
		set(trade, "exitReason", "Target reached");
		set(trade, "exitIndex", 59);
		invokePrivate("queueWinnerFollowThroughSignal", new Class<?>[] { Map.class, trade.getClass(), LocalDate.class }, contexts, trade, NORMAL_DAY);
		return contextHasEventCode(context, "WFT");
	}

	private static boolean mymBreadthEvent(boolean confirming) throws Exception {
		FuturesManager.FuturesStrategySettings settings = settingsForSourceStrategies();
		setToggle(settings, "mymBreadthConfirmation", true, 5);
		settings.mymBreadthMinAlignedMarkets = confirming ? 2 : 3;
		List<Object> mymBars = flatBars("MYM", NORMAL_DAY, LocalTime.of(9, 30), 160, basePrice("MYM"));
		setChannelBreakout(mymBars, 80, basePrice("MYM"), Trend.LONG, "MYM");
		Map<String, Object> contexts = new HashMap<String, Object>();
		contexts.put("MYM", context("MYM", mymBars, settings));
		for (String symbol : Arrays.asList("ES", "NQ", "M2K")) {
			List<Object> bars = flatBars(symbol, NORMAL_DAY, LocalTime.of(9, 30), 160, basePrice(symbol));
			if (confirming || !"M2K".equals(symbol)) {
				setChannelBreakout(bars, 80, basePrice(symbol), Trend.LONG, symbol);
			}
			contexts.put(symbol, context(symbol, bars, settings));
		}
		invokePrivate("addMymBreadthConfirmationSignalEvents", new Class<?>[] { Map.class }, contexts);
		return contextHasEventCode(contexts.get("MYM"), "MYMBR");
	}

	private static FuturesManager.FuturesStrategySettings settingsForSourceStrategies() throws Exception {
		FuturesManager.FuturesStrategySettings settings = settingsFor("ORB");
		disableAllToggles(settings);
		settings.requireHigherTimeframeGuard = false;
		settings.maxInitialRiskTicks = 500.0;
		settings.microShadowMinVolumeRatio = 0.0;
		settings.microShadowMaxRiskTicks = 500.0;
		settings.microShadowMinTrendSlopeTicks = 0.0;
		settings.microEchoMinVolumeRatio = 0.0;
		settings.microEchoMaxRiskTicks = 500.0;
		settings.microEchoMinTrendSlopeTicks = 0.0;
		settings.winnerFollowThroughMinVolumeRatio = 0.0;
		settings.winnerFollowThroughMinBodyPct = 0.0;
		settings.winnerFollowThroughMaxRiskTicks = 500.0;
		settings.winnerFollowThroughMinTrendSlopeTicks = 0.0;
		settings.winnerFollowThroughLongMinRsi = 0.0;
		settings.winnerFollowThroughMinSourcePnl = 0.0;
		settings.mymBreadthMaxRiskTicks = 500.0;
		settings.mymBreadthMinVolumeRatio = 0.0;
		settings.mymBreadthMinBodyPct = 0.0;
		settings.mymBreadthMinTrendSlopeTicks = 0.0;
		return settings;
	}

	private static void disableAllToggles(FuturesManager.FuturesStrategySettings settings) throws Exception {
		for (String field : Arrays.asList(
			"orb", "lateOrbContinuation", "openingMomentum", "sweep", "priorDayBreakout", "vwapPullback",
			"vwapReclaim", "vwapMeanReversion", "fvg", "closeMomentum", "afternoonContinuation",
			"marketIntradayMomentum", "keltnerScalp", "keltnerReversion", "microScalp", "microShadow",
			"microEcho", "winnerFollowThrough", "trendLadder", "rangeCompressionBreakout", "valueAreaReclaim",
			"mclEiaContinuation", "mclCrudeSessionOpen", "mymIndexConfirmation", "mymOrbRetest",
			"mymBreadthConfirmation", "mclTrendContinuation", "rangeMidpointContinuation"
		)) {
			setToggle(settings, field, false, 0);
		}
	}

	private static void setToggle(FuturesManager.FuturesStrategySettings settings, String field, boolean enabled, int maxTrades) throws Exception {
		Field toggle = FuturesManager.FuturesStrategySettings.class.getField(field);
		toggle.set(settings, new FuturesManager.StrategyToggle(enabled, maxTrades));
	}

	private static List<Object> invokeBuildSignals(Scenario scenario) throws Exception {
		return invokeBuildSignals(scenario, null);
	}

	private static List<Object> invokeBuildSignals(Scenario scenario, Map<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>> orderFlow) throws Exception {
		return invokeBuildSignals(scenario, orderFlow, new ArrayList<Object>());
	}

	private static List<Object> invokeBuildSignals(Scenario scenario, Map<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>> orderFlow, List<Object> fifteenMinuteBars) throws Exception {
		Object config = newPrivate("com.tradingbot.FuturesManager$BacktestConfig");
		set(config, "symbol", scenario.symbol);
		set(config, "strategySettings", scenario.settings);
		Object spec = instrumentFor(scenario.symbol);
		Method method = FuturesManager.class.getDeclaredMethod(
			"buildSignals",
			FuturesManager.InstrumentSpec.class,
			List.class,
			List.class,
			List.class,
			List.class,
			config.getClass(),
			Map.class
		);
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<Object> signals = (List<Object>) method.invoke(null, spec, scenario.bars, scenario.previousBars, fifteenMinuteBars, new ArrayList<Object>(), config, orderFlow);
		return signals;
	}

	private static Map<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>> orderFlowMap(
		String symbol,
		LocalDate day,
		LocalTime time,
		double depthImbalance5,
		double tapeDelta,
		String bookFlip,
		String absorption
	) throws Exception {
		Map<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>> byDay = new HashMap<LocalDate, Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot>>();
		Map<LocalTime, LiveRuntimeState.OrderFlowSnapshot> byTime = new HashMap<LocalTime, LiveRuntimeState.OrderFlowSnapshot>();
		LiveRuntimeState.OrderFlowSnapshot snapshot = new LiveRuntimeState.OrderFlowSnapshot();
		snapshot.symbol = symbol;
		snapshot.available = true;
		snapshot.fresh = true;
		snapshot.ageSeconds = 0L;
		snapshot.lastUpdatedAt = day.toString() + " " + time.toString();
		snapshot.bestBid = basePrice(symbol);
		snapshot.bestAsk = basePrice(symbol) + tickSize(symbol);
		snapshot.spreadTicks = 1.0;
		snapshot.depthImbalance3 = depthImbalance5;
		snapshot.depthImbalance5 = depthImbalance5;
		snapshot.depthImbalance10 = depthImbalance5;
		snapshot.topBookImbalance = depthImbalance5;
		snapshot.tapeDelta = tapeDelta;
		if (tapeDelta >= 0.0) {
			snapshot.aggressiveBuyVolume = tapeDelta;
		} else {
			snapshot.aggressiveSellVolume = Math.abs(tapeDelta);
		}
		snapshot.bookFlip = bookFlip;
		snapshot.absorption = absorption;
		snapshot.flowState = depthImbalance5 < -0.25 ? "ASK_HEAVY" : (depthImbalance5 > 0.25 ? "BID_HEAVY" : "BALANCED");
		byTime.put(time, snapshot);
		byDay.put(day, byTime);
		return byDay;
	}

	private static boolean hasSignalCode(List<Object> signals, String code) throws Exception {
		for (Object signal : signals) {
			if (code.equals(getString(signal, "strategyCode"))) {
				return true;
			}
		}
		return false;
	}

	private static Object sourceSignal(String code, String name, String side, int signalIndex, int executionIndex, double base) throws Exception {
		Object signal = newPrivate("com.tradingbot.FuturesManager$Signal");
		set(signal, "strategyCode", code);
		set(signal, "strategyName", name);
		set(signal, "side", side);
		set(signal, "entryIndex", signalIndex);
		set(signal, "entryPrice", base);
		set(signal, "stopPrice", base - 1.0);
		set(signal, "targetPrice", base + 2.0);
		set(signal, "maxHoldBars", 20);
		set(signal, "notes", "synthetic source signal");
		return signalEvent("MES", signal, NORMAL_DAY, LocalTime.of(9, 30).plusMinutes(executionIndex), executionIndex);
	}

	private static Object signalEvent(String symbol, Object signal, LocalDate day, LocalTime entryTime, int executionIndex) throws Exception {
		Object event = newPrivate("com.tradingbot.FuturesManager$SignalEvent");
		set(event, "symbol", symbol);
		set(event, "signal", signal);
		set(event, "day", day);
		set(event, "entryTime", entryTime);
		set(event, "executionIndex", executionIndex);
		return event;
	}

	private static void addEvent(Object context, LocalDate day, Object event) throws Exception {
		@SuppressWarnings("unchecked")
		Map<LocalDate, List<Object>> eventsByDay = (Map<LocalDate, List<Object>>) get(context, "eventsByDay");
		List<Object> events = eventsByDay.get(day);
		if (events == null) {
			events = new ArrayList<Object>();
			eventsByDay.put(day, events);
		}
		events.add(event);
	}

	private static boolean contextHasEventCode(Object context, String code) throws Exception {
		@SuppressWarnings("unchecked")
		Map<LocalDate, List<Object>> eventsByDay = (Map<LocalDate, List<Object>>) get(context, "eventsByDay");
		for (List<Object> events : eventsByDay.values()) {
			for (Object event : events) {
				Object signal = get(event, "signal");
				if (signal != null && code.equals(getString(signal, "strategyCode"))) {
					return true;
				}
			}
		}
		return false;
	}

	private static Object context(String symbol, List<Object> bars, FuturesManager.FuturesStrategySettings settings) throws Exception {
		Object context = newPrivate("com.tradingbot.FuturesManager$PortfolioSymbolContext");
		Object config = newPrivate("com.tradingbot.FuturesManager$BacktestConfig");
		set(config, "symbol", symbol);
		set(config, "strategySettings", settings);
		Map<LocalDate, List<Object>> byDay = new HashMap<LocalDate, List<Object>>();
		LocalDate day = (LocalDate) get(bars.get(0), "marketDate");
		byDay.put(day, bars);
		Map<LocalDate, Map<LocalTime, Integer>> indexByDayTime = new HashMap<LocalDate, Map<LocalTime, Integer>>();
		Map<LocalTime, Integer> byTime = new HashMap<LocalTime, Integer>();
		for (int index = 0; index < bars.size(); index++) {
			byTime.put((LocalTime) get(bars.get(index), "marketTime"), Integer.valueOf(index));
		}
		indexByDayTime.put(day, byTime);
		set(context, "symbol", symbol);
		set(context, "spec", instrumentFor(symbol));
		set(context, "config", config);
		set(context, "byDay", byDay);
		set(context, "indexByDayTime", indexByDayTime);
		set(context, "eventsByDay", new HashMap<LocalDate, List<Object>>());
		return context;
	}

	private static Object invokePrivate(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		return method.invoke(null, args);
	}

	private static List<Object> previousBars(String symbol) throws Exception {
		List<Object> bars = flatBars(symbol, NORMAL_DAY.minusDays(1), LocalTime.of(9, 30), 390, basePrice(symbol));
		double tick = tickSize(symbol);
		for (int index = 0; index < bars.size(); index++) {
			setCandle(bars, index, basePrice(symbol), basePrice(symbol) + (4 * tick), basePrice(symbol) - (4 * tick), basePrice(symbol), Trend.FLAT, symbol);
		}
		return bars;
	}

	private static List<Object> flatBars(String symbol, LocalDate day, LocalTime start, int count, double base) throws Exception {
		List<Object> bars = new ArrayList<Object>();
		for (int index = 0; index < count; index++) {
			LocalTime time = start.plusMinutes(index);
			bars.add(bar(symbol, day, time, base, base + (2 * tickSize(symbol)), base - (2 * tickSize(symbol)), base, Trend.FLAT));
		}
		return bars;
	}

	private static Object bar(String symbol, LocalDate day, LocalTime time, double open, double high, double low, double close, Trend trend) throws Exception {
		Object bar = newPrivate("com.tradingbot.FuturesManager$Bar");
		double tick = tickSize(symbol);
		double vwap = trend == Trend.LONG ? close - (2 * tick) : trend == Trend.SHORT ? close + (2 * tick) : close;
		double ema20 = trend == Trend.LONG ? close - tick : trend == Trend.SHORT ? close + tick : close;
		double ema9 = trend == Trend.LONG ? ema20 + tick : trend == Trend.SHORT ? ema20 - tick : close;
		double ema50 = trend == Trend.LONG ? ema20 - tick : trend == Trend.SHORT ? ema20 + tick : close;
		double range = Math.max(tick, high - low);
		set(bar, "displayTime", day + " " + time);
		set(bar, "marketDate", day);
		set(bar, "marketTime", time);
		set(bar, "open", open);
		set(bar, "high", high);
		set(bar, "low", low);
		set(bar, "close", close);
		set(bar, "volume", 1200.0);
		set(bar, "vwap", vwap);
		set(bar, "ema9", ema9);
		set(bar, "ema20", ema20);
		set(bar, "ema50", ema50);
		set(bar, "atr14", Math.max(tick * 8.0, 1.0));
		set(bar, "rsi14", trend == Trend.SHORT ? 42.0 : 58.0);
		set(bar, "volumeSma20", 1000.0);
		set(bar, "rangeTicks", range / tick);
		set(bar, "bodyPct", Math.abs(close - open) / range * 100.0);
		return bar;
	}

	private static void setCandle(List<Object> bars, int index, double open, double high, double low, double close, Trend trend, String symbol) throws Exception {
		Object existing = bars.get(index);
		LocalDate day = (LocalDate) get(existing, "marketDate");
		LocalTime time = (LocalTime) get(existing, "marketTime");
		bars.set(index, bar(symbol, day, time, open, high, low, close, trend));
	}

	private static void openingRange(List<Object> bars, double high, double low) throws Exception {
		openingRange(bars, high, low, 15);
	}

	private static void openingRange(List<Object> bars, double high, double low, int count) throws Exception {
		String symbol = "MES";
		double close = (high + low) / 2.0;
		for (int index = 0; index < count; index++) {
			setCandle(bars, index, close, high, low, close, Trend.FLAT, symbol);
		}
	}

	private static void setPullbackThenBreak(List<Object> bars, int index, double base, Trend trend, String symbol) throws Exception {
		double tick = tickSize(symbol);
		setCandle(bars, index - 1, base + (2 * tick), base + (4 * tick), base - tick, base + (2 * tick), trend, symbol);
		setCandle(bars, index, base + (5 * tick), base + (10 * tick), base + (4 * tick), base + (9 * tick), trend, symbol);
	}

	private static void setVwapTouchThenReclaim(List<Object> bars, int index, double base, Trend trend, String symbol) throws Exception {
		double tick = tickSize(symbol);
		setCandle(bars, index - 1, base, base + (2 * tick), base - tick, base + tick, Trend.FLAT, symbol);
		set(bars.get(index - 1), "vwap", base);
		setCandle(bars, index, base + (2 * tick), base + (8 * tick), base + tick, base + (7 * tick), trend, symbol);
		set(bars.get(index), "vwap", base + (2 * tick));
	}

	private static void setCompressionBreakout(List<Object> bars, int index, double base, Trend trend, String symbol) throws Exception {
		double tick = tickSize(symbol);
		for (int box = index - 5; box < index; box++) {
			setCandle(bars, box, base, base + tick, base - tick, base, Trend.FLAT, symbol);
			set(bars.get(box), "atr14", tick * 8.0);
		}
		setCandle(bars, index, base + (2 * tick), base + (8 * tick), base + tick, base + (7 * tick), trend, symbol);
		set(bars.get(index), "atr14", tick * 8.0);
	}

	private static void setKeltnerBreakout(List<Object> bars, int index, double base, Trend trend, String symbol) throws Exception {
		double tick = tickSize(symbol);
		setCandle(bars, index - 1, base + (4 * tick), base + (8 * tick), base + (3 * tick), base + (7 * tick), trend, symbol);
		set(bars.get(index - 1), "ema20", base + (2 * tick));
		set(bars.get(index - 1), "atr14", tick * 4.0);
		setCandle(bars, index, base + (8 * tick), base + (14 * tick), base + (7 * tick), base + (13 * tick), trend, symbol);
		set(bars.get(index), "ema20", base + (6 * tick));
		set(bars.get(index), "ema9", base + (8 * tick));
		set(bars.get(index), "ema50", base + (5 * tick));
		set(bars.get(index), "atr14", tick * 4.0);
	}

	private static void setKeltnerReclaim(List<Object> bars, int index, double base, Trend trend, String symbol) throws Exception {
		double tick = tickSize(symbol);
		setCandle(bars, index - 1, base - (8 * tick), base - (5 * tick), base - (10 * tick), base - (9 * tick), Trend.SHORT, symbol);
		set(bars.get(index - 1), "ema20", base);
		set(bars.get(index - 1), "atr14", tick * 4.0);
		setCandle(bars, index, base - (6 * tick), base - (2 * tick), base - (7 * tick), base - (3 * tick), trend, symbol);
		set(bars.get(index), "ema20", base);
		set(bars.get(index), "atr14", tick * 4.0);
		set(bars.get(index), "rsi14", 40.0);
	}

	private static void setMeanReversion(List<Object> bars, int index, double base, Trend trend, String symbol) throws Exception {
		double tick = tickSize(symbol);
		setCandle(bars, index - 1, base - (12 * tick), base - (10 * tick), base - (16 * tick), base - (14 * tick), Trend.SHORT, symbol);
		setCandle(bars, index, base - (9 * tick), base - (4 * tick), base - (11 * tick), base - (5 * tick), trend, symbol);
		set(bars.get(index), "vwap", base + (8 * tick));
	}

	private static void setChannelBreakout(List<Object> bars, int index, double base, Trend trend, String symbol) throws Exception {
		double tick = tickSize(symbol);
		for (int prior = Math.max(0, index - 20); prior < index; prior++) {
			setCandle(bars, prior, base + tick, base + (3 * tick), base - tick, base + tick, trend, symbol);
		}
		setCandle(bars, index, base + (5 * tick), base + (12 * tick), base + (4 * tick), base + (10 * tick), trend, symbol);
	}

	private static void setOpeningImpulse(List<Object> bars, double base, String symbol) throws Exception {
		double tick = tickSize(symbol);
		for (int index = 0; index < 30; index++) {
			double close = base + (index * tick);
			setCandle(bars, index, close - tick, close + tick, close - (2 * tick), close, Trend.LONG, symbol);
		}
	}

	private static void setFvgReclaim(List<Object> bars, int index, double base, String symbol) throws Exception {
		double tick = tickSize(symbol);
		setCandle(bars, index - 2, base, base + tick, base - tick, base, Trend.FLAT, symbol);
		setCandle(bars, index - 1, base + (2 * tick), base + (6 * tick), base + tick, base + (5 * tick), Trend.LONG, symbol);
		setCandle(bars, index, base + (7 * tick), base + (9 * tick), base + (6 * tick), base + (8 * tick), Trend.LONG, symbol);
		setCandle(bars, index + 1, base + (6 * tick), base + (9 * tick), base + (5 * tick), base + (8 * tick), Trend.LONG, symbol);
	}

	private static void setMclInventoryRangeAndBreakout(List<Object> bars, double base, String symbol) throws Exception {
		double tick = tickSize(symbol);
		for (int index = 56; index <= 60; index++) {
			setCandle(bars, index, base, base + (5 * tick), base - (5 * tick), base, Trend.FLAT, symbol);
		}
		setCandle(bars, 90, base + (7 * tick), base + (12 * tick), base + (6 * tick), base + (10 * tick), Trend.LONG, symbol);
	}

	private static Scenario validCrudeOpenScenario() throws Exception {
		String symbol = "MCL";
		List<Object> bars = flatBars(symbol, NORMAL_DAY, LocalTime.of(9, 0), 420, basePrice(symbol));
		double tick = tickSize(symbol);
		double base = basePrice(symbol);
		for (int index = 0; index <= 10; index++) {
			setCandle(bars, index, base, base + (5 * tick), base - (5 * tick), base, Trend.FLAT, symbol);
		}
		setCandle(bars, 15, base + (7 * tick), base + (12 * tick), base + (6 * tick), base + (10 * tick), Trend.LONG, symbol);
		return new Scenario(symbol, bars, previousBars(symbol), settingsFor("COPEN"));
	}

	private static Object instrumentFor(String symbol) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("instrumentFor", String.class);
		method.setAccessible(true);
		return method.invoke(null, symbol);
	}

	private static double tickSize(String symbol) throws Exception {
		return getDouble(instrumentFor(symbol), "tickSize");
	}

	private static double basePrice(String symbol) {
		if ("MCL".equals(symbol)) return 70.00;
		if ("MYM".equals(symbol)) return 39000.0;
		if ("M2K".equals(symbol)) return 2300.0;
		if ("NQ".equals(symbol) || "MNQ".equals(symbol)) return 21000.0;
		if ("ES".equals(symbol) || "MES".equals(symbol)) return 5600.0;
		return 2500.0;
	}

	private static String symbolFor(String code) {
		if ("EIA".equals(code) || "COPEN".equals(code) || "MCLTC".equals(code)) return "MCL";
		if ("IDXCONF".equals(code) || "MYMORB2".equals(code)) return "MYM";
		return "MES";
	}

	private static Object newPrivate(String className) throws Exception {
		Class<?> type = Class.forName(className);
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static Object get(Object target, String field) throws Exception {
		Field declared = target.getClass().getDeclaredField(field);
		declared.setAccessible(true);
		return declared.get(target);
	}

	private static String getString(Object target, String field) throws Exception {
		Object value = get(target, field);
		return value == null ? "" : String.valueOf(value);
	}

	private static double getDouble(Object target, String field) throws Exception {
		Object value = get(target, field);
		return value == null ? 0.0 : ((Number) value).doubleValue();
	}

	private static void set(Object target, String field, Object value) throws Exception {
		Field declared = target.getClass().getDeclaredField(field);
		declared.setAccessible(true);
		declared.set(target, value);
	}

	private enum Trend {
		LONG,
		SHORT,
		FLAT
	}

	private static class Scenario {
		private final String symbol;
		private final List<Object> bars;
		private final List<Object> previousBars;
		private final FuturesManager.FuturesStrategySettings settings;

		private Scenario(String symbol, List<Object> bars, List<Object> previousBars, FuturesManager.FuturesStrategySettings settings) {
			this.symbol = symbol;
			this.bars = bars;
			this.previousBars = previousBars;
			this.settings = settings;
		}
	}
}
