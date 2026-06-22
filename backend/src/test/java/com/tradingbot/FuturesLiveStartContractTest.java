package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class FuturesLiveStartContractTest {
	@Test
	public void liveStartRequestPreservesFrontendContractFields() {
		FuturesManager.FuturesRiskSettings savedRisk = new FuturesManager.FuturesRiskSettings();
		savedRisk.accountSize = 50000.0;
		savedRisk.maxTrailingDrawdown = 2000.0;
		savedRisk.dailyLossLimit = 1000.0;
		savedRisk.maxRiskPerTrade = 700.0;
		savedRisk.maxContracts = 50;
		savedRisk.commissionPerContract = 1.24;
		savedRisk.slippageTicks = 1.0;
		savedRisk.profitTarget = 3000.0;

		Map<String, String> params = new HashMap<String, String>();
		params.put("symbol", "MCL");
		params.put("symbols", "MES,MNQ,MCL");
		params.put("executionMode", "TOPSTEPX");
		params.put("fundedProfile", "TOPSTEP_150K");
		params.put("accountId", "123456");
		params.put("strategyPreset", "bestbiasfree");
		params.put("accountSize", "150000");
		params.put("maxTrailingDrawdown", "4500");
		params.put("dailyLossLimit", "3000");
		params.put("maxRiskPerTrade", "900");
		params.put("maxContracts", "150");
		params.put("commissionPerContract", "1.74");
		params.put("slippageTicks", "2");
		params.put("profitTarget", "9000");
		params.put("maxOpenPositions", "4");
		params.put("maxAggregateContracts", "120");
		params.put("maxAggregateMiniUnits", "12");
		params.put("riskSizingMode", "STATIC_WITHDRAW_DAILY");
		params.put("dtmEnabled", "false");

		FuturesLiveRoutes.LiveStartRequest request = FuturesLiveRoutes.liveStartRequestFromParams(
			params::get,
			"MES,MNQ",
			savedRisk
		);

		assertEquals("MCL", request.symbol);
		assertEquals("MES,MNQ,MCL", request.symbols);
		assertEquals("TOPSTEPX", request.executionMode);
		assertEquals("TOPSTEP_150K", request.fundedProfile);
		assertEquals("123456", request.accountId);
		assertEquals("bestbiasfree", request.strategyPreset);
		assertEquals("STATIC_WITHDRAW_DAILY", request.riskSizingMode);
		assertFalse(request.dtmEnabled);
		assertEquals(150000.0, request.accountSize, 0.001);
		assertEquals(4500.0, request.maxTrailingDrawdown, 0.001);
		assertEquals(3000.0, request.dailyLossLimit, 0.001);
		assertEquals(900.0, request.maxRiskPerTrade, 0.001);
		assertEquals(150, request.maxContracts);
		assertEquals(1.74, request.commissionPerContract, 0.001);
		assertEquals(2.0, request.slippageTicks, 0.001);
		assertEquals(9000.0, request.profitTarget, 0.001);
		assertEquals(4, request.maxOpenPositions);
		assertEquals(120, request.maxAggregateContracts);
		assertEquals(12.0, request.maxAggregateMiniUnits, 0.001);
	}

	@Test
	public void liveStartRequestUsesSavedRiskDefaultsWithoutSilentlyDroppingContractFields() {
		FuturesManager.FuturesRiskSettings savedRisk = new FuturesManager.FuturesRiskSettings();
		savedRisk.accountSize = 51000.0;
		savedRisk.maxTrailingDrawdown = 2100.0;
		savedRisk.dailyLossLimit = 1100.0;
		savedRisk.maxRiskPerTrade = 650.0;
		savedRisk.maxContracts = 45;
		savedRisk.commissionPerContract = 1.50;
		savedRisk.slippageTicks = 1.25;
		savedRisk.profitTarget = 2500.0;

		FuturesLiveRoutes.LiveStartRequest request = FuturesLiveRoutes.liveStartRequestFromParams(
			(key) -> null,
			"MES,MNQ",
			savedRisk
		);

		assertEquals("MNQ", request.symbol);
		assertEquals("MES,MNQ", request.symbols);
		assertEquals("SIMULATED", request.executionMode);
		assertEquals("TOPSTEP_50K", request.fundedProfile);
		assertEquals("", request.accountId);
		assertEquals("bestbiasfree", request.strategyPreset);
		assertEquals("DYNAMIC_COMPOUND_MLL", request.riskSizingMode);
		assertTrue(request.dtmEnabled);
		assertEquals(51000.0, request.accountSize, 0.001);
		assertEquals(2100.0, request.maxTrailingDrawdown, 0.001);
		assertEquals(1100.0, request.dailyLossLimit, 0.001);
		assertEquals(650.0, request.maxRiskPerTrade, 0.001);
		assertEquals(45, request.maxContracts);
		assertEquals(1.50, request.commissionPerContract, 0.001);
		assertEquals(1.25, request.slippageTicks, 0.001);
		assertEquals(2500.0, request.profitTarget, 0.001);
		assertEquals(1, request.maxOpenPositions);
		assertEquals(45, request.maxAggregateContracts);
		assertEquals(5.0, request.maxAggregateMiniUnits, 0.001);
	}
}
