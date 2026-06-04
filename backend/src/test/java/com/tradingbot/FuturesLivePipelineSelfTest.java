package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class FuturesLivePipelineSelfTest {
	@TempDir
	Path tempDir;

	@BeforeEach
	public void setUp() {
		TestDatabaseSupport.useTempDatabase(tempDir);
		LiveRuntimeState.clearForTest();
	}

	@AfterEach
	public void tearDown() {
		LiveRuntimeState.clearForTest();
		TestDatabaseSupport.clearTempDatabase();
	}

	@Test
	public void syntheticLivePipelineCoversSymbolsStrategiesAndGuardBlocks() {
		String json = FuturesManager.runLivePipelineSelfTestJson();
		assertTrue(json.contains("\"success\":true"), json);
		assertTrue(json.contains("\"symbols\":\"MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL,GC\""), json);
		assertTrue(json.contains("\"strategyCases\":279"), json);
		assertTrue(json.contains("\"acceptedSubmitGateCases\":279"), json);
		assertTrue(json.contains("\"completedTradeCases\":279"), json);
		assertTrue(json.contains("\"totalCases\":603"), json);
		assertTrue(json.contains("\"failed\":0"), json);
		assertTrue(json.contains("\"fullTradePathVerified\":true"), json);
		assertTrue(json.contains("\"riskConfigEnvelopeOk\":true"), json);
		assertTrue(json.contains("\"dailyLossBaselineOk\":true"), json);
		assertTrue(json.contains("\"strategyCode\":\"IFVG\""), json);
		assertTrue(json.contains("\"case\":\"valid signal reaches submit gate\""), json);
		assertTrue(json.contains("\"case\":\"accepted signal completes through exit engine\""), json);
		assertTrue(json.contains("\"case\":\"unverified broker exposure blocks submit\""), json);
		assertTrue(json.contains("\"case\":\"existing same-symbol exposure blocks submit\""), json);
		assertTrue(json.contains("\"case\":\"portfolio max-open-position guard blocks submit\""), json);
		assertTrue(json.contains("\"case\":\"per-strategy daily cap blocks submit\""), json);
		assertTrue(json.contains("\"case\":\"invalid stop distance blocks submit\""), json);
	}

	@Test
	public void syntheticSelfTestIgnoresCurrentLiveOrderFlowMarks() {
		String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		LiveRuntimeState.recordRealtimeEvent(
			"market",
			"GatewayQuote",
			"",
			"CON.F.US.MES.M26",
			"MES",
			"{\"bid\":7558.50,\"ask\":7558.75}",
			now
		);

		String json = FuturesManager.runLivePipelineSelfTestJson();

		assertTrue(json.contains("\"success\":true"), json);
		assertTrue(json.contains("\"failed\":0"), json);
		assertTrue(json.contains("\"acceptedSubmitGateCases\":279"), json);
	}
}
