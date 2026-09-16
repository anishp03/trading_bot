package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

public class FuturesLiveDailyRiskBaselineTest {
	@Test
	public void brokerDayStartBalanceIgnoresPriorDayClosedPnl() {
		String brokerMetricsJson = "{"
			+ "\"success\":true,"
			+ "\"currentBalance\":49122.56,"
			+ "\"trades\":["
			+ "{\"closed\":true,\"pnl\":-385.71,\"createdAt\":\"2026-06-03T18:45:50.477128+00:00\"},"
			+ "{\"closed\":true,\"pnl\":-390.18,\"createdAt\":\"2026-06-03T15:18:51.073279+00:00\"},"
			+ "{\"closed\":true,\"pnl\":-65.60,\"createdAt\":\"2026-06-03T15:03:32.644035+00:00\"}"
			+ "]}";

		double dayStart = FuturesManager.brokerDayStartBalanceForLiveRiskForTest(
			brokerMetricsJson,
			49122.56,
			LocalDate.of(2026, 6, 4)
		);

		assertEquals(49122.56, dayStart, 0.001);
	}

	@Test
	public void brokerDayStartBalanceSubtractsCurrentDayClosedPnl() {
		String brokerMetricsJson = "{"
			+ "\"success\":true,"
			+ "\"currentBalance\":49022.56,"
			+ "\"trades\":["
			+ "{\"closed\":true,\"pnl\":-100.00,\"createdAt\":\"2026-06-04T14:15:00.000000+00:00\"},"
			+ "{\"closed\":false,\"pnl\":0,\"createdAt\":\"2026-06-04T14:10:00.000000+00:00\"},"
			+ "{\"closed\":true,\"pnl\":-385.71,\"createdAt\":\"2026-06-03T18:45:50.477128+00:00\"}"
			+ "]}";

		double dayStart = FuturesManager.brokerDayStartBalanceForLiveRiskForTest(
			brokerMetricsJson,
			49022.56,
			LocalDate.of(2026, 6, 4)
		);

		assertEquals(49122.56, dayStart, 0.001);
	}

	@Test
	public void brokerDayStartBalanceIncludesSameDayEntrySideTradeCosts() {
		String brokerMetricsJson = "{"
			+ "\"success\":true,"
			+ "\"currentBalance\":49652.33,"
			+ "\"trades\":["
			+ "{\"closed\":true,\"grossPnl\":270.25,\"totalFees\":212.79,\"pnl\":57.46,\"createdAt\":\"2026-06-16T20:09:38.121208+00:00\"},"
			+ "{\"closed\":false,\"grossPnl\":0.0,\"totalFees\":212.79,\"pnl\":0.0,\"createdAt\":\"2026-06-16T20:09:34.010209+00:00\"},"
			+ "{\"closed\":true,\"grossPnl\":-162.50,\"totalFees\":14.92,\"pnl\":-177.42,\"createdAt\":\"2026-06-15T20:09:38.121208+00:00\"}"
			+ "]}";

		double dayStart = FuturesManager.brokerDayStartBalanceForLiveRiskForTest(
			brokerMetricsJson,
			49652.33,
			LocalDate.of(2026, 6, 16)
		);

		assertEquals(49807.66, dayStart, 0.001);
	}

	@Test
	public void fundedPnlModeStartsNewSessionAtRiskEquityAfterPriorDayLoss() {
		String brokerMetricsJson = "{"
			+ "\"success\":true,"
			+ "\"currentBalance\":-1001.30,"
			+ "\"currentPnl\":-1001.30,"
			+ "\"riskCurrentBalance\":48998.70,"
			+ "\"equityBalance\":48998.70,"
			+ "\"balanceMode\":\"PNL\","
			+ "\"balanceTracksPnl\":true,"
			+ "\"trades\":["
			+ "{\"closed\":true,\"pnl\":-1001.30,\"createdAt\":\"2026-06-10T15:30:00.000000+00:00\"}"
			+ "]}";

		double dayStart = FuturesManager.brokerDayStartBalanceForLiveRiskForTest(
			brokerMetricsJson,
			48998.70,
			LocalDate.of(2026, 6, 11)
		);

		assertEquals(48998.70, dayStart, 0.001);
	}

	@Test
	public void fundedPnlModeSubtractsOnlySameDayClosedPnlFromRiskEquity() {
		String brokerMetricsJson = "{"
			+ "\"success\":true,"
			+ "\"currentBalance\":-1101.30,"
			+ "\"currentPnl\":-1101.30,"
			+ "\"riskCurrentBalance\":48898.70,"
			+ "\"equityBalance\":48898.70,"
			+ "\"balanceMode\":\"PNL\","
			+ "\"balanceTracksPnl\":true,"
			+ "\"trades\":["
			+ "{\"closed\":true,\"pnl\":-100.00,\"createdAt\":\"2026-06-11T15:30:00.000000+00:00\"},"
			+ "{\"closed\":true,\"pnl\":-1001.30,\"createdAt\":\"2026-06-10T15:30:00.000000+00:00\"}"
			+ "]}";

		double dayStart = FuturesManager.brokerDayStartBalanceForLiveRiskForTest(
			brokerMetricsJson,
			48898.70,
			LocalDate.of(2026, 6, 11)
		);

		assertEquals(48998.70, dayStart, 0.001);
	}
}
