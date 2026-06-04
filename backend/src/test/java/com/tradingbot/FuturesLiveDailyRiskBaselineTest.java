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
}
