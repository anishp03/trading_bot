package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

public class FuturesLiveTradeLifecycleTest {
	@TempDir
	Path tempDir;

	@BeforeEach
	public void setUp() {
		TestDatabaseSupport.useTempDatabase(tempDir);
	}

	@AfterEach
	public void tearDown() {
		TestDatabaseSupport.clearTempDatabase();
		LiveRuntimeState.clearForTest();
	}

	@Test
	public void syntheticTradesFlowThroughOrderFlowDtmExitReasonsAndLogs() throws Exception {
		String json = FuturesManager.runSyntheticLiveTradeLifecycleSelfTestJsonForTest();

		assertTrue(json.contains("\"success\":true"), json);
		assertTrue(json.contains("\"symbols\":\"MES,MYM,MCL\""), json);
		assertTrue(json.contains("\"acceptedEntries\":3"), json);
		assertTrue(json.contains("\"completedTrades\":3"), json);
		assertTrue(json.contains("\"standardTargetManagedTrades\":1"), json);
		assertTrue(json.contains("\"decisionRows\":6"), json);
		assertTrue(json.contains("\"entryReasonPayloads\":true"), json);
		assertTrue(json.contains("\"exitReasonPayloads\":true"), json);
		assertTrue(json.contains("\"thinkingLogFlow\":true"), json);
		assertTrue(json.contains("\"brokerOrdersSuppressed\":true"), json);
		assertTrue(json.contains("\"provenance\":\"synthetic_live_lifecycle_self_test\""), json);
		assertTrue(json.contains("DTM_PARTIAL_HALF_RUNNER_EXTENDED"), json);
		assertTrue(json.contains("SIMULATED_DTM_PARTIAL_CLOSE"), json);
		assertTrue(json.contains("DTM_CUT_EARLY_THESIS_FAILED"), json);
		assertFalse(json.contains("DTM_MOVE_STOP_BREAKEVEN"), json);
		assertTrue(json.contains("\"tradeReason\""), json);
		assertTrue(json.contains("\"entryReasoning\""), json);
		assertTrue(json.contains("\"exitReasoning\""), json);

		assertEquals(6, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveSignalDecisions"));
		assertEquals(3, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveSignalDecisions WHERE status LIKE 'ACCEPTED%'"));
		assertEquals(3, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveSignalDecisions WHERE status LIKE 'SIMULATED_%EXIT'"));
		assertEquals(3, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveThinkingLog WHERE eventType = 'ORDER_SUBMITTED'"));
		assertEquals(3, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveThinkingLog WHERE eventType = 'POSITION_EXITED'"));
		assertEquals(9, TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveThinkingLog WHERE detailsJson LIKE '%\"provenance\":\"synthetic_live_lifecycle_self_test\"%'"));
		assertTrue(TestDatabaseSupport.countRows("SELECT COUNT(*) FROM FuturesLiveThinkingLog WHERE eventType = 'DTM_DECISION'") >= 2);
	}
}
