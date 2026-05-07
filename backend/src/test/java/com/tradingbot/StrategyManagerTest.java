package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StrategyManagerTest {
	@TempDir
	Path tempDir;

	@BeforeEach
	public void setUp() {
		TestDatabaseSupport.useTempDatabase(tempDir);
	}

	@AfterEach
	public void tearDown() {
		TestDatabaseSupport.clearTempDatabase();
	}

	@Test
	public void defaultStrategySettingsLoadWithValidatedDefaults() {
		StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();

		assertNotNull(settings.orb);
		assertNotNull(settings.ifvg);
		assertNotNull(settings.vwapPullback);
		assertNotNull(settings.vwapMeanReversion);
		assertNotNull(settings.gapGo);
		assertTrue(settings.hasEnabledStrategies());
		assertTrue(settings.orb.isEnabled);
		assertTrue(settings.ifvg.isEnabled);
		assertTrue(settings.vwapPullback.isEnabled);
		assertTrue(settings.vwapMeanReversion.isEnabled);
		assertTrue(settings.gapGo.isEnabled);
		assertEquals("ORB", settings.orb.strategyCode);
		assertEquals("Opening Range Breakout", settings.orb.strategyName);
		assertEquals("IFVG", settings.ifvg.strategyCode);
		assertEquals("Inverse Fair Value Gap", settings.ifvg.strategyName);
		assertEquals("VWAP", settings.vwapPullback.strategyCode);
		assertEquals("VWAP Trend Pullback", settings.vwapPullback.strategyName);
		assertEquals("GAPGO", settings.gapGo.strategyCode);
		assertEquals("Gap and Go Continuation", settings.gapGo.strategyName);

		String json = StrategyManager.getStrategySettingsJson();
		assertTrue(json.contains("\"enabledStrategies\""));
		assertTrue(json.contains("Opening Range Breakout"));
		assertTrue(json.contains("Inverse Fair Value Gap"));
		assertTrue(json.contains("VWAP Trend Pullback"));
		assertTrue(json.contains("Gap and Go Continuation"));
	}

	@Test
	public void saveStrategySettingsRejectsNullAndNormalizesOutOfRangeValues() {
		assertFalse(StrategyManager.saveStrategySettings(null));

		StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();
		settings.orb.isEnabled = false;
		settings.orb.trendTimeframe = "2Hour";
		settings.orb.riskPerTradePct = 99.0;
		settings.orb.maxTradesPerDay = 99;
		settings.orb.orbWindowMinutes = 1;
		settings.ifvg.signalTimeframe = "2Min";
		settings.ifvg.minimumGapPct = -3.0;
		settings.ifvg.maxTradesPerDay = 0;
		settings.vwapPullback.maxTradesPerDay = 99;
		settings.gapGo.minimumGapPct = 99.0;

		assertTrue(StrategyManager.saveStrategySettings(settings));

		StrategyManager.StrategySettings reloaded = StrategyManager.loadStrategySettings();
		assertFalse(reloaded.orb.isEnabled);
		assertEquals("30Min", reloaded.orb.trendTimeframe);
		assertEquals(10.0, reloaded.orb.riskPerTradePct, 0.001);
		assertEquals(5, reloaded.orb.maxTradesPerDay);
		assertEquals(5, reloaded.orb.orbWindowMinutes);
		assertEquals("5Min", reloaded.ifvg.signalTimeframe);
		assertEquals(0.01, reloaded.ifvg.minimumGapPct, 0.001);
		assertEquals(5, reloaded.ifvg.maxTradesPerDay);
		assertEquals(5, reloaded.vwapPullback.maxTradesPerDay);
		assertEquals(2.0, reloaded.gapGo.minimumGapPct, 0.001);
	}

	@Test
	public void evaluateLiveSignalsReturnsEmptyForMissingInputsAndDisabledStrategies() {
		StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();
		LocalDate tradingDay = LocalDate.of(2024, 4, 22);
		List<AlpacaManager.CachedBar> bars = new ArrayList<AlpacaManager.CachedBar>();
		bars.add(bar(tradingDay, LocalTime.of(9, 30), 100.0, 101.0, 99.0, 100.0, 1000.0));

		assertTrue(StrategyManager.evaluateLiveSignals(null, null, null, null, null, tradingDay).isEmpty());

		settings.orb.isEnabled = false;
		settings.ifvg.isEnabled = false;
		settings.vwapPullback.isEnabled = false;
		settings.gapGo.isEnabled = false;
		assertTrue(StrategyManager.evaluateLiveSignals(settings, bars, bars, bars, bars, tradingDay).isEmpty());
	}

	@Test
	public void evaluateLiveSignalsDetectsOpeningRangeBreakoutDrive() {
		StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();
		settings.orb.isEnabled = true;
		settings.orb.orbWindowMinutes = 5;
		settings.orb.reclaimWindowBars = 3;
		settings.orb.requireTrendAlignment = false;
		settings.ifvg.isEnabled = false;
		settings.vwapPullback.isEnabled = false;
		settings.gapGo.isEnabled = false;

		LocalDate tradingDay = LocalDate.of(2024, 4, 22);
		List<AlpacaManager.CachedBar> oneMinuteBars = new ArrayList<AlpacaManager.CachedBar>();
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 30), 99.98, 100.0, 99.96, 99.99, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 31), 99.99, 100.0, 99.97, 99.98, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 32), 99.98, 99.99, 99.96, 99.99, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 33), 99.99, 100.0, 99.97, 99.98, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 34), 99.98, 99.99, 99.96, 99.99, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 35), 99.99, 100.0, 99.98, 100.0, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(10, 5), 100.01, 100.06, 100.0, 100.04, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(10, 6), 100.04, 100.1, 100.03, 100.09, 1000.0));

		List<StrategyManager.LiveSignalSnapshot> signals = StrategyManager.evaluateLiveSignals(
			settings,
			oneMinuteBars,
			new ArrayList<AlpacaManager.CachedBar>(),
			new ArrayList<AlpacaManager.CachedBar>(),
			new ArrayList<AlpacaManager.CachedBar>(),
			tradingDay
		);

		assertFalse(signals.isEmpty());
		StrategyManager.LiveSignalSnapshot signal = signals.get(0);
		assertEquals("ORB", signal.strategyCode);
		assertEquals("Opening Range Breakout", signal.strategyName);
		assertEquals("LONG", signal.side);
		assertEquals(100.04, signal.entryPrice, 0.001);
		assertEquals("2024-04-22 10:05", signal.openedAt);
		assertEquals("2024-04-22 10:06", signal.closedAt);
		assertTrue(signal.tradeNotes.contains("ORB long"));
	}

	@Test
	public void evaluateLiveSignalsDetectsVwapTrendPullback() {
		StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();
		settings.orb.isEnabled = false;
		settings.ifvg.isEnabled = false;
		settings.vwapPullback.isEnabled = true;
		settings.vwapPullback.minimumGapPct = 0.01;
		settings.vwapPullback.reclaimWindowBars = 5;
		settings.vwapPullback.requireTrendAlignment = false;
		settings.gapGo.isEnabled = false;

		LocalDate tradingDay = LocalDate.of(2024, 4, 22);
		List<AlpacaManager.CachedBar> oneMinuteBars = new ArrayList<AlpacaManager.CachedBar>();
		for (int index = 0; index < 20; index++) {
			oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 30).plusMinutes(index), 100.0, 100.01, 99.99, 100.0, 1000.0));
		}
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 50), 100.0, 100.01, 99.99, 100.0, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 51), 100.02, 100.08, 100.01, 100.07, 1200.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 52), 100.07, 100.13, 100.06, 100.12, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 53), 100.12, 100.14, 100.1, 100.13, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 54), 100.13, 100.14, 100.11, 100.12, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 55), 100.12, 100.13, 100.1, 100.11, 1000.0));

		List<StrategyManager.LiveSignalSnapshot> signals = StrategyManager.evaluateLiveSignals(
			settings,
			oneMinuteBars,
			new ArrayList<AlpacaManager.CachedBar>(),
			new ArrayList<AlpacaManager.CachedBar>(),
			new ArrayList<AlpacaManager.CachedBar>(),
			tradingDay
		);

		assertFalse(signals.isEmpty());
		StrategyManager.LiveSignalSnapshot signal = signals.get(0);
		assertEquals("VWAP", signal.strategyCode);
		assertEquals("VWAP Trend Pullback", signal.strategyName);
		assertEquals("LONG", signal.side);
		assertEquals("2024-04-22 09:51", signal.openedAt);
		assertTrue(signal.tradeNotes.contains("VWAP pullback long"));
	}

	@Test
	public void evaluateLiveSignalsDetectsGapAndGoContinuation() {
		StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();
		settings.orb.isEnabled = false;
		settings.ifvg.isEnabled = false;
		settings.vwapPullback.isEnabled = false;
		settings.gapGo.isEnabled = true;
		settings.gapGo.orbWindowMinutes = 5;
		settings.gapGo.minimumGapPct = 0.4;
		settings.gapGo.breakoutBufferPct = 0.0;
		settings.gapGo.requireTrendAlignment = false;

		LocalDate previousDay = LocalDate.of(2024, 4, 19);
		LocalDate tradingDay = LocalDate.of(2024, 4, 22);
		List<AlpacaManager.CachedBar> oneMinuteBars = new ArrayList<AlpacaManager.CachedBar>();
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 30), 100.6, 100.62, 100.58, 100.61, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 31), 100.61, 100.63, 100.59, 100.62, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 32), 100.62, 100.64, 100.6, 100.63, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 33), 100.63, 100.65, 100.61, 100.64, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 34), 100.64, 100.66, 100.62, 100.65, 1000.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 35), 100.66, 100.76, 100.65, 100.74, 1500.0));
		oneMinuteBars.add(bar(tradingDay, LocalTime.of(9, 36), 100.74, 100.85, 100.73, 100.84, 1000.0));
		List<AlpacaManager.CachedBar> trendBars = new ArrayList<AlpacaManager.CachedBar>();
		trendBars.add(bar(previousDay, LocalTime.of(15, 30), 99.8, 100.2, 99.7, 100.0, 1000.0));

		List<StrategyManager.LiveSignalSnapshot> signals = StrategyManager.evaluateLiveSignals(
			settings,
			oneMinuteBars,
			new ArrayList<AlpacaManager.CachedBar>(),
			trendBars,
			new ArrayList<AlpacaManager.CachedBar>(),
			tradingDay
		);

		assertFalse(signals.isEmpty());
		StrategyManager.LiveSignalSnapshot signal = signals.get(0);
		assertEquals("GAPGO", signal.strategyCode);
		assertEquals("Gap and Go Continuation", signal.strategyName);
		assertEquals("LONG", signal.side);
		assertEquals("2024-04-22 09:35", signal.openedAt);
		assertTrue(signal.tradeNotes.contains("Gap-and-go long"));
	}

	private static AlpacaManager.CachedBar bar(
		LocalDate date,
		LocalTime time,
		double open,
		double high,
		double low,
		double close,
		double volume
	) {
		return new AlpacaManager.CachedBar(
			date.toString() + " " + time.toString(),
			date,
			time,
			open,
			high,
			low,
			close,
			volume
		);
	}
}
