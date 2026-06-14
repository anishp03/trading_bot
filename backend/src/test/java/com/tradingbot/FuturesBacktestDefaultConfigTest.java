package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FuturesBacktestDefaultConfigTest {
	@TempDir
	Path tempDir;

	@AfterEach
	public void tearDown() {
		TestDatabaseSupport.clearTempDatabase();
	}

	@Test
	public void portfolioBacktestDefaultsStartAtAvailableFuturesDataBoundary() {
		TestDatabaseSupport.useTempDatabase(tempDir);

		String defaults = FuturesManager.getPortfolioBacktestDefaultConfigJson();

		assertTrue(defaults.contains("\"startDate\":\"2024-05-01\""), defaults);
	}

	@Test
	public void portfolioBacktestDefaultsKeepDataBoundaryWhenUsingSourceRun() throws Exception {
		TestDatabaseSupport.useTempDatabase(tempDir);
		FuturesManager.getPortfolioBacktestDefaultConfigJson();
		insertRecommendedSourceRun("2025-06-10");

		String defaults = FuturesManager.getPortfolioBacktestDefaultConfigJson();

		assertTrue(defaults.contains("\"success\":true"), defaults);
		assertTrue(defaults.contains("\"startDate\":\"2024-05-01\""), defaults);
	}

	private static void insertRecommendedSourceRun(String startDate) throws Exception {
		String sql = "INSERT INTO FuturesPortfolioBacktests ("
			+ "fundedProfile, symbols, startDate, endDate, startingBalance, endingBalance, totalProfit, returnPct, "
			+ "winRate, numTrades, profitFactor, maxDrawdownPct, maxTrailingDrawdown, dailyLossLimit, maxRiskPerTrade, "
			+ "maxContracts, maxOpenPositions, maxAggregateContracts, maxAggregateMiniUnits, maxConcurrentPositions, "
			+ "maxConcurrentContracts, maxConcurrentMiniUnits, maxNotionalExposure, maxIntradayLoss, maxAggregateMae, "
			+ "trailingThreshold, dailyLossBreaches, trailingDrawdownBreaches, maeBreaches, overlapRejections, "
			+ "exposureRejections, riskRejections, ruleViolation, continueAfterRuleViolation, ruleMessage, dataSource, "
			+ "createdAt, portfolioSettingsJson"
			+ ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, "TOPSTEP_50K");
			pstmt.setString(2, "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL");
			pstmt.setString(3, startDate);
			pstmt.setString(4, "2026-06-10");
			pstmt.setDouble(5, 50000.0);
			pstmt.setDouble(6, 51000.0);
			pstmt.setDouble(7, 1000.0);
			pstmt.setDouble(8, 2.0);
			pstmt.setDouble(9, 70.0);
			pstmt.setInt(10, 10);
			pstmt.setDouble(11, 2.0);
			pstmt.setDouble(12, 3.0);
			pstmt.setDouble(13, 2000.0);
			pstmt.setDouble(14, 1000.0);
			pstmt.setDouble(15, 700.0);
			pstmt.setInt(16, 50);
			pstmt.setInt(17, 3);
			pstmt.setInt(18, 50);
			pstmt.setDouble(19, 5.0);
			pstmt.setInt(20, 3);
			pstmt.setInt(21, 50);
			pstmt.setDouble(22, 5.0);
			pstmt.setDouble(23, 0.0);
			pstmt.setDouble(24, 0.0);
			pstmt.setDouble(25, 0.0);
			pstmt.setDouble(26, 48000.0);
			pstmt.setInt(27, 0);
			pstmt.setInt(28, 0);
			pstmt.setInt(29, 0);
			pstmt.setInt(30, 0);
			pstmt.setInt(31, 0);
			pstmt.setInt(32, 0);
			pstmt.setInt(33, 0);
			pstmt.setInt(34, 1);
			pstmt.setString(35, "");
			pstmt.setString(36, "test");
			pstmt.setString(37, "2026-06-11 08:36:00");
			pstmt.setString(38, "{\"strategyPreset\":\"bestbiasfree\",\"dtmEnabled\":true,\"qualitativeRiskEnabled\":true}");
			pstmt.executeUpdate();
		}
	}
}
