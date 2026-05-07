package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LiveBotManagerTest {
	@TempDir
	Path tempDir;

	private AccountManager accountManager;
	private LiveBotManager liveBotManager;

	@BeforeEach
	public void setUp() {
		TestDatabaseSupport.useTempDatabase(tempDir);
		accountManager = new AccountManager();
		liveBotManager = new LiveBotManager(accountManager);
	}

	@AfterEach
	public void tearDown() {
		TestDatabaseSupport.clearTempDatabase();
	}

	@Test
	public void blankEmailRequestsReturnIdleErrorStatuses() {
		LiveBotManager.LiveBotStatus startStatus = liveBotManager.startBot(" ", "SPY", 1000.0, 100.0, 50.0);
		LiveBotManager.LiveBotStatus stopStatus = liveBotManager.stopBot("", false);
		LiveBotManager.LiveBotStatus currentStatus = liveBotManager.getStatus(null);

		assertFalse(startStatus.success);
		assertFalse(startStatus.running);
		assertEquals("Missing account email.", startStatus.message);
		assertFalse(stopStatus.success);
		assertEquals("Missing account email.", stopStatus.message);
		assertFalse(currentStatus.success);
		assertEquals("Missing account email.", currentStatus.message);
	}

	@Test
	public void startBotFailsBeforeNetworkCallWhenBrokerKeysAreMissing() {
		String email = "livebot@tradingbot.com";
		assertTrue(accountManager.registerAccount("Live Bot User", email, "secret"));

		LiveBotManager.LiveBotStatus status = liveBotManager.startBot(email, "qqq", -1.0, -1.0, -1.0);

		assertFalse(status.success);
		assertFalse(status.running);
		assertEquals("Broker keys not configured.", status.message);
		assertEquals("SPY", status.symbol);
	}

	@Test
	public void stopBotReportsAlreadyStoppedWhenNoSessionExists() {
		LiveBotManager.LiveBotStatus status = liveBotManager.stopBot("nobody@tradingbot.com", false);

		assertTrue(status.success);
		assertFalse(status.running);
		assertEquals("Live bot is already stopped.", status.message);
	}

	@Test
	public void statusToJsonSerializesRoundedNumbersNestedTradeDataAndEscapedText() {
		LiveBotManager.LiveBotStatus status = new LiveBotManager.LiveBotStatus();
		status.success = true;
		status.running = true;
		status.message = "Running \"paper\" bot\nnow";
		status.symbol = "AAPL";
		status.perTradeBuyingPower = 1234.567;
		status.takeProfit = 100.004;
		status.lossLimit = 50.006;
		status.cash = 999.999;
		status.enabledStrategies.add("Opening Range Breakout");

		LiveBotManager.ActiveTrade activeTrade = new LiveBotManager.ActiveTrade();
		activeTrade.id = 7;
		activeTrade.strategyCode = "ORB";
		activeTrade.strategyName = "Opening Range Breakout";
		activeTrade.side = "LONG";
		activeTrade.qty = 2.345;
		activeTrade.entryPrice = 101.239;
		activeTrade.stopPrice = 99.991;
		activeTrade.targetPrice = 105.555;
		activeTrade.effectiveStopPrice = 100.001;
		activeTrade.effectiveTargetPrice = 104.999;
		activeTrade.currentPrice = 102.222;
		activeTrade.unrealizedPnl = 2.345;
		activeTrade.openedAt = "2026-04-26 10:00";
		activeTrade.tradeNotes = "Entry note";
		status.activeTrade = activeTrade;
		status.activeTrades.add(activeTrade);

		LiveBotManager.TradeLog tradeLog = new LiveBotManager.TradeLog();
		tradeLog.id = 8;
		tradeLog.strategyCode = "IFVG";
		tradeLog.strategyName = "Inverse Fair Value Gap";
		tradeLog.time = "2026-04-26 11:00";
		tradeLog.closedAt = "2026-04-26 11:10";
		tradeLog.side = "SHORT";
		tradeLog.qty = 1.234;
		tradeLog.entry = 200.555;
		tradeLog.exit = null;
		tradeLog.pnl = -4.444;
		tradeLog.status = "OPEN";
		tradeLog.tradeNotes = "Still open";
		status.tradeLogs.add(tradeLog);

		String json = liveBotManager.statusToJson(status);

		assertTrue(json.contains("\"success\":true"));
		assertTrue(json.contains("\"running\":true"));
		assertTrue(json.contains("\"message\":\"Running \\\"paper\\\" bot\\nnow\""));
		assertTrue(json.contains("\"perTradeBuyingPower\":1234.57"));
		assertTrue(json.contains("\"cash\":1000.0"));
		assertTrue(json.contains("\"enabledStrategies\":[\"Opening Range Breakout\"]"));
		assertTrue(json.contains("\"activeTrade\":{\"id\":7"));
		assertTrue(json.contains("\"activeTrades\":[{\"id\":7"));
		assertTrue(json.contains("\"qty\":2.35"));
		assertTrue(json.contains("\"exit\":null"));
		assertTrue(json.contains("\"pnl\":-4.44"));
	}
}
