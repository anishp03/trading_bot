package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class OrbxVabsPresetParityTest {
	@TempDir
	Path tempDir;

	@BeforeEach
	public void useTempDatabase() {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesManager.initializeStore();
	}

	@AfterEach
	public void clearTempDatabase() {
		TestDatabaseSupport.clearTempDatabase();
	}

	@Test
	public void bestBiasFreeSeedsOrbxRetestOnlyCap8ForM2k() {
		FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings("M2K", FuturesManager.strategyPresetSlot("bestbiasfree"));

		assertTrue(settings.orbEventPack.enabled);
		assertEquals(8, settings.orbEventPackMaxContracts);
		assertEquals("M2K_RETEST_LONG", settings.orbEventPackSetups);
		assertFalse(settings.orbEventPackRequireSimilarOrb);
		assertFalse(settings.orbEventPackGroupOrbFamilyDailyLimit);
	}

	@Test
	public void bestBiasFreeSeedsVabsMorningCoreForAllowedSymbolsOnly() {
		FuturesManager.FuturesStrategySettings m2k = FuturesManager.loadFuturesStrategySettings("M2K", FuturesManager.strategyPresetSlot("bestbiasfree"));
		FuturesManager.FuturesStrategySettings mcl = FuturesManager.loadFuturesStrategySettings("MCL", FuturesManager.strategyPresetSlot("bestbiasfree"));

		assertTrue(m2k.volumePriceManipulation.enabled);
		assertEquals("VABS", m2k.volumePriceMode);
		assertEquals(600, m2k.volumePriceStartMinute);
		assertEquals(719, m2k.volumePriceEndMinute);
		assertEquals(24, m2k.volumePriceLookbackBars);
		assertEquals(1.20, m2k.volumePriceMinVolumeRatio, 0.0001);
		assertEquals(0.95, m2k.volumePriceQuietVolumeRatio, 0.0001);
		assertEquals(45.0, m2k.volumePriceMinBodyPct, 0.0001);
		assertEquals(120.0, m2k.volumePriceMaxRiskTicks, 0.0001);
		assertEquals(1.50, m2k.volumePriceRewardRisk, 0.0001);
		assertTrue(m2k.volumePriceRequireHigherTimeframe);
		assertEquals(0, m2k.volumePriceMaxContracts);
		assertEquals(4, m2k.volumePriceManipulation.maxTradesPerDay);
		assertFalse(mcl.volumePriceManipulation.enabled);
	}
}
