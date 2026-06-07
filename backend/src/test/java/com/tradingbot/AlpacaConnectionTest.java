package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AlpacaConnectionTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void clearRuntimeProperties() {
		System.clearProperty("tradingbot.runtimeRoot");
		System.clearProperty("tradingbot.equityMarketDataDir");
	}
	
	@Test
	public void alpacaMetadataStaysConsistent() {
		AlpacaManager alpaca = new AlpacaManager("demoApiKey", "demoSecretKey");

		assertNotNull(alpaca, "The Alpaca manager should be created.");
		assertEquals("Alpaca", AlpacaManager.getBrokerName(), "The broker name should stay Alpaca.");
		assertEquals("https://paper-api.alpaca.markets/v2", AlpacaManager.getBaseUrl(), "The API URL should stay fixed.");
		assertArrayEquals(new String[] {"SPY", "QQQ", "AAPL", "NVDA", "TSLA"}, AlpacaManager.getSupportedSymbols());
	}

	@Test
	public void normalizeSymbolDefaultsBlankInputAndUppercasesUserInput() {
		assertEquals("SPY", AlpacaManager.normalizeSymbol(null));
		assertEquals("SPY", AlpacaManager.normalizeSymbol("   "));
		assertEquals("AAPL", AlpacaManager.normalizeSymbol(" aapl "));
	}

	@Test
	public void accountSnapshotParsesSuccessfulJsonAndRejectsMalformedResponses() {
		StubAlpacaManager connectedAlpaca = new StubAlpacaManager(
			"{\"account_name\":\"Paper Trader\",\"cash\":\"1234.56\",\"buying_power\":\"2000\",\"equity\":\"3456.78\",\"portfolio_value\":\"3500\"}"
		);

		AlpacaManager.AccountSnapshot connectedSnapshot = connectedAlpaca.getAccountSnapshot();
		assertTrue(connectedSnapshot.connected);
		assertEquals("Paper Trader", connectedSnapshot.accountName);
		assertEquals(1234.56, connectedSnapshot.cash, 0.001);
		assertEquals(2000.0, connectedSnapshot.buyingPower, 0.001);
		assertEquals(3456.78, connectedSnapshot.equity, 0.001);
		assertEquals(3500.0, connectedSnapshot.portfolioValue, 0.001);

		AlpacaManager.AccountSnapshot disconnectedSnapshot = new StubAlpacaManager("Connection failed").getAccountSnapshot();
		assertFalse(disconnectedSnapshot.connected);
		assertEquals("", disconnectedSnapshot.accountName);
		assertEquals(0.0, disconnectedSnapshot.cash, 0.001);
	}

	@Test
	public void connectedAccountNameFallsBackAcrossSupportedJsonKeys() {
		assertEquals("Named Account", new StubAlpacaManager("{\"name\":\"Named Account\"}").getConnectedAccountName());
		assertEquals("ACC-123", new StubAlpacaManager("{\"account_number\":\"ACC-123\"}").getConnectedAccountName());
		assertEquals("Unavailable", new StubAlpacaManager("not-json").getConnectedAccountName());
	}

	@Test
	public void loadCachedBarsFiltersToMarketSessionAndNormalizesReversedDates() {
		LocalDate tradingDay = LocalDate.of(2024, 4, 22);

		List<AlpacaManager.CachedBar> bars = AlpacaManager.loadCachedBars("spy", tradingDay, tradingDay, "1Min");
		assertFalse(bars.isEmpty(), "The checked-in SPY market-data cache should have one-minute bars for 2024-04-22.");

		AlpacaManager.CachedBar firstBar = bars.get(0);
		AlpacaManager.CachedBar lastBar = bars.get(bars.size() - 1);
		assertEquals(tradingDay, firstBar.marketDate);
		assertFalse(firstBar.marketTime.isBefore(LocalTime.of(9, 30)));
		assertTrue(lastBar.marketTime.isBefore(LocalTime.of(16, 0)));

		List<AlpacaManager.CachedBar> reversedRange = AlpacaManager.loadCachedBars("spy", tradingDay, tradingDay.minusDays(1), "bad-timeframe");
		assertEquals(bars.size(), reversedRange.size(), "A reversed date range should be normalized to the start date.");
	}

	@Test
	public void fetchBarsRejectsInvalidDateRangesBeforeMakingNetworkRequests() {
		AlpacaManager alpaca = new AlpacaManager("demoApiKey", "demoSecretKey");
		ZonedDateTime end = ZonedDateTime.of(2024, 4, 22, 10, 0, 0, 0, ZoneId.of("America/New_York"));
		ZonedDateTime start = end.plusMinutes(1);

		assertTrue(alpaca.fetchBars("SPY", null, end, "1Min").isEmpty());
		assertTrue(alpaca.fetchBars("SPY", start, end, "1Min").isEmpty());
	}

	@Test
	public void marketDataStatusJsonDescribesCheckedInCache() {
		String json = AlpacaManager.getMarketDataStatusJson();

		assertTrue(json.contains("\"symbols\":[\"SPY\",\"QQQ\",\"AAPL\",\"NVDA\",\"TSLA\"]"));
		assertTrue(json.contains("\"storagePath\":"));
		assertTrue(json.contains("market_data"));
		assertTrue(json.contains("\"hasData\":true"));
	}

	@Test
	public void runtimeRootRoutesAlpacaMarketDataStatus() throws Exception {
		System.setProperty("tradingbot.runtimeRoot", tempDir.toString());
		Path marketData = tempDir.resolve("market_data");
		Files.createDirectories(marketData);
		Files.write(marketData.resolve("status.properties"), (
			"startDate=2024-04-22\n"
				+ "endDate=2024-04-22\n"
				+ "lastUpdatedAt=2026-06-06T14:20:00Z\n"
				+ "totalBars=5\n"
		).getBytes(StandardCharsets.UTF_8));

		String json = AlpacaManager.getMarketDataStatusJson();

		assertTrue(json.contains("\"storagePath\":\"" + jsonEscape(marketData.toString()) + "\""), json);
		assertTrue(json.contains("\"hasData\":true"), json);
	}

	private static String jsonEscape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static class StubAlpacaManager extends AlpacaManager {
		private final String accountInfo;

		private StubAlpacaManager(String accountInfo) {
			super("demoApiKey", "demoSecretKey");
			this.accountInfo = accountInfo;
		}

		@Override
		public String getAccountInfo() {
			return accountInfo;
		}
	}
}
