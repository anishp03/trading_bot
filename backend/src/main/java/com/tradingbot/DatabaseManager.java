package com.tradingbot;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
public class DatabaseManager {
	private static final int SQLITE_BUSY_TIMEOUT_MS = 30000;
	private static volatile boolean sqliteWalConfigured = false;

	public static String getDatabaseUrl() {
		return databaseUrl();
	}

	public static Connection getConnection() throws SQLException {
		Connection conn = DriverManager.getConnection(databaseUrl());
		configureConnection(conn);
		return conn;
	}

	public static Connection getReadOnlyConnection() throws SQLException {
		Connection conn = getConnection();
		try (Statement stmt = conn.createStatement()) {
			stmt.execute("PRAGMA query_only = ON");
		}
		return conn;
	}

	private static void configureConnection(Connection conn) throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.execute("PRAGMA busy_timeout = " + SQLITE_BUSY_TIMEOUT_MS);
			stmt.execute("PRAGMA foreign_keys = ON");
			stmt.execute("PRAGMA temp_store = MEMORY");
			stmt.execute("PRAGMA synchronous = NORMAL");
		}
		ensureWalMode(conn);
	}

	private static void ensureWalMode(Connection conn) throws SQLException {
		if (sqliteWalConfigured) {
			return;
		}
		synchronized (DatabaseManager.class) {
			if (sqliteWalConfigured) {
				return;
			}
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("PRAGMA journal_mode = WAL");
				sqliteWalConfigured = true;
			} catch (SQLException e) {
				if (isSqliteBusy(e)) {
					return;
				}
				throw e;
			}
		}
	}

	private static boolean isSqliteBusy(SQLException e) {
		String message = e == null ? "" : String.valueOf(e.getMessage()).toUpperCase();
		return message.contains("SQLITE_BUSY") || message.contains("DATABASE IS LOCKED");
	}

	public static String getDatabasePath() {
		return databaseFile().getAbsolutePath();
	}

	private static String databaseUrl() {
		return "jdbc:sqlite:" + databaseFile().getAbsolutePath();
	}

	private static File databaseFile() {
		File db = new File(RuntimePaths.databasePath()).getAbsoluteFile();
		File parent = db.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		return db;
	}
	public static void initializeDatabase() 
	{
		try(Connection conn = getConnection();
				Statement stmt = conn.createStatement()) {
			System.out.println("Connected to SQLite successfully.");
			String createAccountTable = "CREATE TABLE IF NOT EXISTS Account ("
					+ "accountID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "accountName TEXT, "
					+ "email TEXT UNIQUE, "
					+ "phoneNumber TEXT, "
					+ "address TEXT, "
					+ "passwordHash TEXT, "
					+ "startingBalance REAL, "
					+ "currentBalance REAL, "
					+ "accountType TEXT, "
					+ "createdAt TEXT, "
					+ "role TEXT DEFAULT 'admin', "
					+ "brokerApiKey TEXT, "
					+ "brokerSecretKey TEXT"
					+ ");";
			stmt.execute(createAccountTable);
			ensureColumnExists(conn, "Account", "phoneNumber", "TEXT");
			ensureColumnExists(conn, "Account", "address", "TEXT");
			ensureColumnExists(conn, "Account", "role", "TEXT DEFAULT 'admin'");
			ensureColumnExists(conn, "Account", "brokerApiKey", "TEXT");
			ensureColumnExists(conn, "Account", "brokerSecretKey", "TEXT");
			backfillAccountRoles(conn);
			bootstrapAdminAccount(conn);
			String createAccountSessionTable = "CREATE TABLE IF NOT EXISTS AccountSession ("
					+ "sessionID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "sessionToken TEXT UNIQUE NOT NULL, "
					+ "accountID INTEGER NOT NULL, "
					+ "role TEXT NOT NULL, "
					+ "createdAt TEXT NOT NULL, "
					+ "lastSeenAt TEXT, "
					+ "expiresAt TEXT NOT NULL, "
					+ "revokedAt TEXT, "
					+ "FOREIGN KEY (accountID) REFERENCES Account(accountID)"
					+ ");";
			stmt.execute(createAccountSessionTable);
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_account_session_token ON AccountSession(sessionToken)");
			String createStrategiesTable = "CREATE TABLE IF NOT EXISTS Strategies ("
					+ "strategyID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "strategyName TEXT, "
					+ "description TEXT, "
					+ "timeframe TEXT, "
					+ "riskPerTradePct REAL, "
					+ "takeProfitPct REAL, "
					+ "stopLossPct REAL, "
					+ "maxTradesPerDay INTEGER, "
					+ "isEnabled INTEGER"
					+ ");";
			stmt.execute(createStrategiesTable);
			StrategyManager.initializeStrategyStore(conn);
			String createLiveBotTable = "CREATE TABLE IF NOT EXISTS Live_Bot ("
					+ "liveBotID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "accountID INTEGER, "
					+ "strategyID INTEGER, "
					+ "status TEXT, "
					+ "symbols TEXT, "
					+ "startedAt TEXT, "
					+ "lastUpdatedAt TEXT, "
					+ "equity REAL, "
					+ "totalProfit REAL, "
					+ "returnPct REAL, "
					+ "winRate REAL, "
					+ "numTrades INTEGER, "
					+ "FOREIGN KEY (accountID) REFERENCES Account(accountID), "
					+ "FOREIGN KEY (strategyID) REFERENCES Strategies(strategyID)"
					+ ");";
			stmt.execute(createLiveBotTable);
			String createBacktestsTable = "CREATE TABLE IF NOT EXISTS Backtests ("
					+ "backtestID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "accountID INTEGER, "
					+ "strategyID INTEGER, "
					+ "backtestName TEXT, "
					+ "symbols TEXT, "
					+ "timeframe TEXT, "
					+ "startDate TEXT, "
					+ "endDate TEXT, "
					+ "startingCapital REAL, "
					+ "endingCapital REAL, "
					+ "totalProfit REAL, "
					+ "returnPct REAL, "
					+ "winRate REAL, "
					+ "numTrades INTEGER, "
					+ "profitFactor REAL, "
					+ "maxDrawdownPct REAL, "
					+ "createdAt TEXT, "
					+ "FOREIGN KEY (accountID) REFERENCES Account(accountID), "
					+ "FOREIGN KEY (strategyID) REFERENCES Strategies(strategyID)"
					+ ");";
			stmt.execute(createBacktestsTable);
			String createTradesTable = "CREATE TABLE IF NOT EXISTS Trades ("
					+ "tradeID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "backtestID INTEGER, "
					+ "liveBotID INTEGER, "
					+ "symbol TEXT, "
					+ "side TEXT, "
					+ "qty REAL, "
					+ "entryPrice REAL, "
					+ "exitPrice REAL, "
					+ "openedAt TEXT, "
					+ "closedAt TEXT, "
					+ "strategyCode TEXT, "
					+ "strategyName TEXT, "
					+ "tradeNotes TEXT, "
					+ "status TEXT, "
					+ "pnl REAL, "
					+ "FOREIGN KEY (backtestID) REFERENCES Backtests(backtestID), "
					+ "FOREIGN KEY (liveBotID) REFERENCES Live_Bot(liveBotID)"
					+ ");";
			stmt.execute(createTradesTable);
			ensureColumnExists(conn, "Trades", "tradeNotes", "TEXT");
			ensureColumnExists(conn, "Trades", "strategyCode", "TEXT");
			ensureColumnExists(conn, "Trades", "strategyName", "TEXT");
			backfillTradeStrategyMetadata(conn);
			System.out.println("All Tables verified.");
			System.out.println("Database has been completely initialized.");
		} 
		catch(SQLException e) 
		{
			System.out.println("Error trying to connect to database or creating table.");
			e.printStackTrace();
		}
	}
	private static void ensureColumnExists(Connection conn, String tableName, String columnName, String columnType) throws SQLException
	{
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) 
		{
			while (rs.next()) 
			{
				if (columnName.equalsIgnoreCase(rs.getString("name"))) 
				{
					return;
				}
			}
		}
		try (Statement stmt = conn.createStatement()) 
		{
			stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType);
		}
	}
	public static int generateStrategyBacktest(
		String symbol,
		String startDate,
		String endDate,
		double startingCapital,
		double perTradeBuyingPower,
		double takeProfit,
		double lossLimit
	) 
	{
		try (Connection conn = getConnection()) 
		{
			conn.setAutoCommit(false);
			try 
			{
				String normalizedSymbol = normalizeSymbol(symbol);
				double normalizedStartingCapital = positiveOrDefault(startingCapital, 25000.0);
				double normalizedPerTradeBuyingPower = positiveOrDefault(perTradeBuyingPower, normalizedStartingCapital);
				double normalizedTakeProfit = positiveOrDefault(takeProfit, 1000.0);
				double normalizedLossLimit = positiveOrDefault(lossLimit, 500.0);
				LocalDate defaultEndDate = LocalDate.now();
				LocalDate parsedStartDate = parseDateOrDefault(startDate, defaultEndDate.minusMonths(6));
				LocalDate parsedEndDate = parseDateOrDefault(endDate, defaultEndDate);
				if (parsedEndDate.isBefore(parsedStartDate)) 
				{
					parsedEndDate = parsedStartDate;
				}
				StrategyManager.StrategyBacktest backtest = StrategyManager.buildStrategyBacktest(
					normalizedSymbol,
					parsedStartDate,
					parsedEndDate,
					normalizedStartingCapital,
					normalizedPerTradeBuyingPower,
					normalizedTakeProfit,
					normalizedLossLimit
				);
				if (backtest == null || backtest.startDate == null || backtest.endDate == null) 
				{
					conn.rollback();
					return -1;
				}
				String backtestName = "run_test_" + getNextRunNumber(conn);
				int backtestId = insertBacktest(
					conn,
					backtestName,
					normalizedSymbol,
					backtest.timeframeSummary,
					backtest.startDate.toString(),
					backtest.endDate.toString(),
					normalizedStartingCapital,
					backtest.endingCapital,
					backtest.totalProfit,
					backtest.returnPct,
					backtest.winRate,
					backtest.trades.size(),
					backtest.profitFactor,
					backtest.maxDrawdownPct,
					Instant.now().toString()
				);
				for (int i = 0; i < backtest.trades.size(); i++) 
				{
					StrategyManager.TradeRecord trade = backtest.trades.get(i);
					insertTrade(
						conn,
						backtestId,
						normalizedSymbol,
						trade.side,
						trade.qty,
						trade.entryPrice,
						trade.exitPrice,
						trade.openedAt,
						trade.closedAt,
						trade.strategyCode,
						strategyNameForCode(trade.strategyCode),
						trade.tradeNotes,
						"CLOSED",
						trade.pnl
					);
				}
				conn.commit();
				System.out.println("Backtest generated successfully.");
				return backtestId;
			} 
			catch (SQLException e) 
			{
				conn.rollback();
				System.out.println("Failed to generate backtest.");
				e.printStackTrace();
				return -1;
			} 
			finally 
			{
				conn.setAutoCommit(true);
			}
		} 
		catch (SQLException e) 
		{
			System.out.println("Error generating backtest data.");
			e.printStackTrace();
			return -1;
		}
	}
	public static boolean clearBacktests()
	{
		try (Connection conn = getConnection())
		{
			conn.setAutoCommit(false);
			try (Statement stmt = conn.createStatement())
			{
				stmt.executeUpdate("DELETE FROM Trades WHERE backtestID IS NOT NULL");
				stmt.executeUpdate("DELETE FROM Backtests");
				stmt.executeUpdate("DELETE FROM sqlite_sequence WHERE name IN ('Trades', 'Backtests')");
				conn.commit();
				return true;
			}
			catch (SQLException e)
			{
				conn.rollback();
				e.printStackTrace();
				return false;
			}
			finally
			{
				conn.setAutoCommit(true);
			}
		}
		catch (SQLException e)
		{
			e.printStackTrace();
			return false;
		}
	}
	private static BacktestResult buildBacktestPreview(
		String symbol,
		LocalDate startDate,
		LocalDate endDate,
		double startingCapital,
		double takeProfit,
		double lossLimit
	) {
		List<AlpacaManager.CachedBar> bars = AlpacaManager.loadCachedBars(symbol, startDate, endDate);
		if (bars.isEmpty()) {
			return null;
		}
		List<BacktestTrade> trades = buildPreviewTrades(bars, startingCapital, takeProfit, lossLimit);
		double currentEquity = startingCapital;
		double peakEquity = startingCapital;
		double grossProfit = 0.0;
		double grossLoss = 0.0;
		double maxDrawdownPct = 0.0;
		int winningTrades = 0;

		for (int i = 0; i < trades.size(); i++) {
			BacktestTrade trade = trades.get(i);
			currentEquity = roundToTwoDecimals(currentEquity + trade.pnl);

			if (trade.pnl >= 0.0) {
				grossProfit += trade.pnl;
				winningTrades++;
			} else {
				grossLoss += Math.abs(trade.pnl);
			}

			if (currentEquity > peakEquity) {
				peakEquity = currentEquity;
			}

			if (peakEquity > 0.0) {
				double currentDrawdown = ((peakEquity - currentEquity) / peakEquity) * 100.0;
				if (currentDrawdown > maxDrawdownPct) {
					maxDrawdownPct = currentDrawdown;
				}
			}
		}

		BacktestResult result = new BacktestResult();
		result.startDate = bars.get(0).marketDate;
		result.endDate = bars.get(bars.size() - 1).marketDate;
		result.endingCapital = roundToTwoDecimals(currentEquity);
		result.totalProfit = roundToTwoDecimals(currentEquity - startingCapital);
		result.returnPct = startingCapital <= 0.0 ? 0.0 : roundToTwoDecimals((result.totalProfit / startingCapital) * 100.0);
		result.winRate = trades.isEmpty() ? 0.0 : roundToTwoDecimals((winningTrades * 100.0) / trades.size());
		result.profitFactor = grossLoss == 0.0 ? roundToTwoDecimals(grossProfit) : roundToTwoDecimals(grossProfit / grossLoss);
		result.maxDrawdownPct = roundToTwoDecimals(maxDrawdownPct);
		result.trades = trades;
		return result;
	}
	private static List<BacktestTrade> buildPreviewTrades(
		List<AlpacaManager.CachedBar> bars,
		double startingCapital,
		double takeProfit,
		double lossLimit
	) {
		List<BacktestTrade> trades = new ArrayList<BacktestTrade>();
		if (bars == null || bars.size() < 2) {
			return trades;
		}

		int tradeCount = bars.size() >= 9 ? 3 : bars.size() >= 4 ? 2 : 1;

		for (int i = 0; i < tradeCount; i++) {
			int entryIndex = Math.min((i * bars.size()) / tradeCount, bars.size() - 2);
			int exitIndex = Math.min((((i + 1) * bars.size()) / tradeCount) - 1, bars.size() - 1);
			if (exitIndex <= entryIndex) {
				exitIndex = Math.min(bars.size() - 1, entryIndex + 1);
			}

			AlpacaManager.CachedBar entryBar = bars.get(entryIndex);
			AlpacaManager.CachedBar exitBar = bars.get(exitIndex);
			String side = i % 2 == 0 ? "LONG" : "SHORT";
			double qty = Math.max(1.0, Math.floor((startingCapital * 0.08) / Math.max(1.0, entryBar.close)));
			double rawPnl = "SHORT".equals(side)
				? (entryBar.close - exitBar.close) * qty
				: (exitBar.close - entryBar.close) * qty;
			double cappedPnl = roundToTwoDecimals(Math.max(-lossLimit, Math.min(takeProfit, rawPnl)));
			double exitPrice = "SHORT".equals(side)
				? roundToTwoDecimals(entryBar.close - (cappedPnl / qty))
				: roundToTwoDecimals(entryBar.close + (cappedPnl / qty));

			trades.add(new BacktestTrade(
				side,
				qty,
				roundToTwoDecimals(entryBar.close),
				exitPrice,
				entryBar.displayTime,
				exitBar.displayTime,
				buildTradeNotes(side, i + 1),
				cappedPnl
			));
		}

		return trades;
	}
	private static String buildTradeNotes(String side, int tradeNumber) {
		return "Backtest placeholder trade " + tradeNumber + " (" + side + ")";
	}
	private static int getNextRunNumber(Connection conn) throws SQLException 
	{
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(backtestID), 0) + 1 AS nextRunNumber FROM Backtests")) 
		{
			if (rs.next()) 
			{
				return rs.getInt("nextRunNumber");
			}
		}
		return 1;
	}
	private static int insertBacktest(
		Connection conn,
		String backtestName,
		String symbols,
		String timeframe,
		String startDate,
		String endDate,
		double startingCapital,
		double endingCapital,
		double totalProfit,
		double returnPct,
		double winRate,
		int numTrades,
		double profitFactor,
		double maxDrawdownPct,
		String createdAt
	) throws SQLException 
	{
		String sql = "INSERT INTO Backtests (backtestName, symbols, timeframe, startDate, endDate, startingCapital, endingCapital, totalProfit, returnPct, winRate, numTrades, profitFactor, maxDrawdownPct, createdAt) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) 
		{
			pstmt.setString(1, backtestName);
			pstmt.setString(2, symbols);
			pstmt.setString(3, timeframe);
			pstmt.setString(4, startDate);
			pstmt.setString(5, endDate);
			pstmt.setDouble(6, startingCapital);
			pstmt.setDouble(7, endingCapital);
			pstmt.setDouble(8, totalProfit);
			pstmt.setDouble(9, returnPct);
			pstmt.setDouble(10, winRate);
			pstmt.setInt(11, numTrades);
			pstmt.setDouble(12, profitFactor);
			pstmt.setDouble(13, maxDrawdownPct);
			pstmt.setString(14, createdAt);
			pstmt.executeUpdate();
			try (ResultSet rs = pstmt.getGeneratedKeys()) 
			{
				if (rs.next()) 
				{
					return rs.getInt(1);
				}
			}
		}
		throw new SQLException("Failed to create backtest row.");
	}
	private static void insertTrade(
		Connection conn,
		int backtestId,
		String symbol,
		String side,
		double qty,
		double entryPrice,
		double exitPrice,
		String openedAt,
		String closedAt,
		String strategyCode,
		String strategyName,
		String tradeNotes,
		String status,
		double pnl
	) throws SQLException 
	{
		String sql = "INSERT INTO Trades (backtestID, symbol, side, qty, entryPrice, exitPrice, openedAt, closedAt, strategyCode, strategyName, tradeNotes, status, pnl) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) 
		{
			pstmt.setInt(1, backtestId);
			pstmt.setString(2, symbol);
			pstmt.setString(3, side);
			pstmt.setDouble(4, qty);
			pstmt.setDouble(5, entryPrice);
			pstmt.setDouble(6, exitPrice);
			pstmt.setString(7, openedAt);
			pstmt.setString(8, closedAt);
			pstmt.setString(9, normalizeStrategyCode(strategyCode, tradeNotes));
			pstmt.setString(10, isBlank(strategyName) ? strategyNameForCode(strategyCode) : strategyName);
			pstmt.setString(11, tradeNotes);
			pstmt.setString(12, status);
			pstmt.setDouble(13, pnl);
			pstmt.executeUpdate();
		}
	}

	public static int createLiveBotRun(int accountId, String symbol, String startedAt, double equity) {
		String sql = "INSERT INTO Live_Bot (accountID, status, symbols, startedAt, lastUpdatedAt, equity, totalProfit, returnPct, winRate, numTrades) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try (Connection conn = getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			if (accountId > 0) {
				pstmt.setInt(1, accountId);
			} else {
				pstmt.setNull(1, Types.INTEGER);
			}
			pstmt.setString(2, "RUNNING");
			pstmt.setString(3, normalizeSymbol(symbol));
			pstmt.setString(4, startedAt);
			pstmt.setString(5, startedAt);
			pstmt.setDouble(6, roundToTwoDecimals(equity));
			pstmt.setDouble(7, 0.0);
			pstmt.setDouble(8, 0.0);
			pstmt.setDouble(9, 0.0);
			pstmt.setInt(10, 0);
			pstmt.executeUpdate();

			try (ResultSet rs = pstmt.getGeneratedKeys()) {
				if (rs.next()) {
					return rs.getInt(1);
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return -1;
	}

	public static boolean updateLiveBotRun(
		int liveBotId,
		String status,
		String lastUpdatedAt,
		double equity,
		double totalProfit,
		double returnPct,
		double winRate,
		int numTrades
	) {
		String sql = "UPDATE Live_Bot SET status = ?, lastUpdatedAt = ?, equity = ?, totalProfit = ?, returnPct = ?, winRate = ?, numTrades = ? "
			+ "WHERE liveBotID = ?";

		try (Connection conn = getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, status);
			pstmt.setString(2, lastUpdatedAt);
			pstmt.setDouble(3, roundToTwoDecimals(equity));
			pstmt.setDouble(4, roundToTwoDecimals(totalProfit));
			pstmt.setDouble(5, roundToTwoDecimals(returnPct));
			pstmt.setDouble(6, roundToTwoDecimals(winRate));
			pstmt.setInt(7, numTrades);
			pstmt.setInt(8, liveBotId);
			return pstmt.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static int insertLiveTrade(
		int liveBotId,
		String symbol,
		String side,
		double qty,
		double entryPrice,
		String openedAt,
		String strategyCode,
		String strategyName,
		String tradeNotes,
		String status
	) {
		String sql = "INSERT INTO Trades (liveBotID, symbol, side, qty, entryPrice, exitPrice, openedAt, closedAt, strategyCode, strategyName, tradeNotes, status, pnl) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try (Connection conn = getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			pstmt.setInt(1, liveBotId);
			pstmt.setString(2, normalizeSymbol(symbol));
			pstmt.setString(3, side);
			pstmt.setDouble(4, roundToTwoDecimals(qty));
			pstmt.setDouble(5, roundToTwoDecimals(entryPrice));
			pstmt.setNull(6, Types.REAL);
			pstmt.setString(7, openedAt);
			pstmt.setNull(8, Types.VARCHAR);
			pstmt.setString(9, normalizeStrategyCode(strategyCode, tradeNotes));
			pstmt.setString(10, isBlank(strategyName) ? strategyNameForCode(strategyCode) : strategyName);
			pstmt.setString(11, tradeNotes);
			pstmt.setString(12, status);
			pstmt.setNull(13, Types.REAL);
			pstmt.executeUpdate();

			try (ResultSet rs = pstmt.getGeneratedKeys()) {
				if (rs.next()) {
					return rs.getInt(1);
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

		return -1;
	}

	public static boolean closeLiveTrade(
		int tradeId,
		double exitPrice,
		String closedAt,
		String tradeNotes,
		String status,
		double pnl
	) {
		String sql = "UPDATE Trades SET exitPrice = ?, closedAt = ?, tradeNotes = ?, status = ?, pnl = ? WHERE tradeID = ?";

		try (Connection conn = getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setDouble(1, roundToTwoDecimals(exitPrice));
			pstmt.setString(2, closedAt);
			pstmt.setString(3, tradeNotes);
			pstmt.setString(4, status);
			pstmt.setDouble(5, roundToTwoDecimals(pnl));
			pstmt.setInt(6, tradeId);
			return pstmt.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	private static void backfillTradeStrategyMetadata(Connection conn) throws SQLException {
		String sql = "UPDATE Trades SET strategyCode = ?, strategyName = ? "
			+ "WHERE (strategyCode IS NULL OR TRIM(strategyCode) = '') "
			+ "AND tradeNotes IS NOT NULL AND UPPER(tradeNotes) LIKE ?";
		backfillTradeStrategy(conn, sql, "ORB", "%ORB%");
		backfillTradeStrategy(conn, sql, "IFVG", "%IFVG%");
		backfillTradeStrategy(conn, sql, "VWAP", "%VWAP%");
		backfillTradeStrategy(conn, sql, "GAPGO", "%GAP%");
	}

	private static void backfillTradeStrategy(Connection conn, String sql, String strategyCode, String notePattern) throws SQLException {
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, strategyCode);
			pstmt.setString(2, strategyNameForCode(strategyCode));
			pstmt.setString(3, notePattern);
			pstmt.executeUpdate();
		}
	}

	private static void backfillAccountRoles(Connection conn) throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate("UPDATE Account SET role = 'admin' WHERE role IS NULL OR TRIM(role) = ''");
		}
	}

	private static void bootstrapAdminAccount(Connection conn) throws SQLException {
		String email = System.getenv("TRADINGBOT_BOOTSTRAP_ADMIN_EMAIL");
		String password = System.getenv("TRADINGBOT_BOOTSTRAP_ADMIN_PASSWORD");
		if (isBlank(email) && isBlank(password)) {
			return;
		}
		if (isBlank(email) || isBlank(password)) {
			System.out.println("Bootstrap admin skipped: both TRADINGBOT_BOOTSTRAP_ADMIN_EMAIL and TRADINGBOT_BOOTSTRAP_ADMIN_PASSWORD are required.");
			return;
		}

		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		int existingAccountId = -1;
		try (PreparedStatement check = conn.prepareStatement("SELECT accountID FROM Account WHERE email = ?")) {
			check.setString(1, normalizedEmail);
			try (ResultSet rs = check.executeQuery()) {
				if (rs.next()) {
					existingAccountId = rs.getInt("accountID");
				}
			}
		}

		String name = System.getenv("TRADINGBOT_BOOTSTRAP_ADMIN_NAME");
		if (existingAccountId > 0) {
			if (Boolean.parseBoolean(System.getenv().getOrDefault("TRADINGBOT_BOOTSTRAP_ADMIN_RESET", "false"))) {
				String sql = "UPDATE Account SET accountName = ?, passwordHash = ?, role = 'admin' WHERE accountID = ?";
				try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
					pstmt.setString(1, isBlank(name) ? "Trading Bot Admin" : name.trim());
					pstmt.setString(2, AccountManager.hashPassword(password));
					pstmt.setInt(3, existingAccountId);
					pstmt.executeUpdate();
				}
				try (PreparedStatement revoke = conn.prepareStatement("UPDATE AccountSession SET revokedAt = ? WHERE accountID = ? AND revokedAt IS NULL")) {
					revoke.setString(1, Instant.now().toString());
					revoke.setInt(2, existingAccountId);
					revoke.executeUpdate();
				}
				System.out.println("Bootstrap admin account reset for " + normalizedEmail + ".");
			}
			return;
		}

		String sql = "INSERT INTO Account(accountName, email, phoneNumber, address, passwordHash, createdAt, role) VALUES(?,?,?,?,?,?,?)";
		try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, isBlank(name) ? "Trading Bot Admin" : name.trim());
			pstmt.setString(2, normalizedEmail);
			pstmt.setString(3, "");
			pstmt.setString(4, "");
			pstmt.setString(5, AccountManager.hashPassword(password));
			pstmt.setString(6, Instant.now().toString());
			pstmt.setString(7, "admin");
			pstmt.executeUpdate();
			System.out.println("Bootstrap admin account created for " + normalizedEmail + ".");
		}
	}

	private static String normalizeStrategyCode(String strategyCode, String tradeNotes) {
		if (!isBlank(strategyCode)) {
			return strategyCode.trim().toUpperCase();
		}
		String notes = tradeNotes == null ? "" : tradeNotes.toUpperCase();
		if (notes.contains("IFVG")) {
			return "IFVG";
		}
		if (notes.contains("VWAP")) {
			if (notes.contains("MEAN") || notes.contains("RSI")) {
				return "MRVWAP";
			}
			return "VWAP";
		}
		if (notes.contains("GAP")) {
			return "GAPGO";
		}
		if (notes.contains("ORB")) {
			return "ORB";
		}
		return "";
	}

	private static String strategyNameForCode(String strategyCode) {
		String normalized = strategyCode == null ? "" : strategyCode.trim().toUpperCase();
		if ("ORB".equals(normalized)) {
			return "Opening Range Breakout";
		}
		if ("IFVG".equals(normalized)) {
			return "Inverse Fair Value Gap";
		}
		if ("VWAP".equals(normalized)) {
			return "VWAP Pullback";
		}
		if ("MRVWAP".equals(normalized)) {
			return "VWAP RSI Mean Reversion";
		}
		if ("GAPGO".equals(normalized)) {
			return "Gap Continuation";
		}
		return normalized;
	}

	private static String normalizeSymbol(String symbol) 
	{
		if (symbol == null || symbol.trim().isEmpty()) 
		{
			return "SPY";
		}
		return symbol.trim().toUpperCase();
	}
	private static LocalDate parseDateOrDefault(String value, LocalDate defaultValue) {
		if (value == null || value.trim().isEmpty()) { return defaultValue; }
		try { return LocalDate.parse(value); } catch (DateTimeParseException e) { return defaultValue; }
	}
	private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
	private static double positiveOrDefault(double value, double defaultValue) { return value > 0 ? roundToTwoDecimals(value) : defaultValue; }
	private static String jsonString(String value) {
		String safeValue = value == null ? "" : value;
		safeValue = safeValue
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r");
		return "\"" + safeValue + "\"";
	}
	private static double roundToTwoDecimals(double value) { return Math.round(value * 100.0) / 100.0; }
	private static class BacktestTrade {
		private final String side, openedAt, closedAt, tradeNotes;
		private final double qty, entryPrice, exitPrice, pnl;
		private BacktestTrade(String side, double qty, double entryPrice, double exitPrice, String openedAt, String closedAt, String tradeNotes, double pnl) {
			this.side = side; this.qty = qty; this.entryPrice = entryPrice; this.exitPrice = exitPrice; this.openedAt = openedAt; this.closedAt = closedAt; this.tradeNotes = tradeNotes; this.pnl = pnl;
		}
	}
	private static class BacktestResult {
		private LocalDate startDate, endDate;
		private double endingCapital, totalProfit, returnPct, winRate, profitFactor, maxDrawdownPct;
		private List<BacktestTrade> trades = new ArrayList<BacktestTrade>();
	}
}
