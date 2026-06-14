package com.tradingbot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class FuturesConnectionManager {
	private static final String TRADOVATE = "TRADOVATE";
	private static final String TOPSTEPX = "TOPSTEPX";
	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 120000;
	private static final int TOPSTEPX_CONTRACT_SEARCH_MAX_ATTEMPTS = 3;
	private static final long TOPSTEPX_CONTRACT_SEARCH_RETRY_BASE_MS = 350L;
	private static final long TOPSTEPX_CONTRACT_CACHE_TTL_MS = 15L * 60L * 1000L;
	private static final String ENRICHED_BAR_HEADER = "timestamp,open,high,low,close,volume,vwap,ema9,ema20,ema50,atr14,rsi14,volume_sma20,range_ticks,body_pct\n";
	private static final String SYNTHETIC_LEVEL2_FOLDER = "level2-synthetic";
	private static final String SYNTHETIC_LEVEL2_HEADER = "timestamp,best_bid,best_ask,spread_ticks,depth_imbalance5,tape_delta,cvd,bid_wall_distance_ticks,ask_wall_distance_ticks,bid_stacking,ask_stacking,absorption,liquidity_vacuum,flow_state,source_open,source_high,source_low,source_close,source_volume,source_range_ticks,source_body_pct,source\n";
	private static final String DEFAULT_FUTURES_SYMBOLS = "MES.c.0,MNQ.c.0,M2K.c.0,MYM.c.0,ES.c.0,NQ.c.0,MGC.v.0,MCL.c.0,GC.v.0";
	private static final Map<String, CachedTopstepContracts> TOPSTEPX_CONTRACT_CACHE = new HashMap<String, CachedTopstepContracts>();

	private static class ConnectionConfig {
		private String provider;
		private boolean enabled;
		private String baseUrl;
		private String environment;
		private String username;
		private String apiKey;
		private String password;
		private String secret;
		private String appId;
		private String appVersion;
		private String cid;
		private String accountId;
		private String accountSpec;
		private String dataset;
		private String schema;
		private String symbols;
		private String marketHubUrl;
		private String userHubUrl;
		private String lastTestStatus;
		private String lastTestMessage;
		private String lastTestAt;
		private String updatedAt;
	}

	private static class TopstepAccount {
		private String accountId;
		private String name;
		private boolean active;
		private String createdAt;
		private String updatedAt;
	}

	private static class HttpResult {
		private int statusCode;
		private String body;
	}

	private static class TopstepxOrderAttempt {
		private boolean success;
		private int statusCode;
		private String orderId = "0";
		private String requestJson = "{}";
		private String responseJson = "{}";
		private String responseBody = "";
		private boolean bracketsSubmitted;
	}

	static class InternalBar {
		Instant timestamp;
		String timestampText;
		double open;
		double high;
		double low;
		double close;
		double volume;
		double vwap;
		double ema9;
		double ema20;
		double ema50;
		double atr14;
		double rsi14;
		double volumeSma20;
		double rangeTicks;
		double bodyPct;
	}

	private static class TopstepContract {
		private String id;
		private String name;
		private String description;
		private String symbolId;
		private boolean active;
		private boolean inferred;
		private double tickSize;
		private double tickValue;
	}

	private static class CachedTopstepContracts {
		private List<TopstepContract> contracts;
		private long cachedAtMillis;
	}

	static class TopstepxRealtimeConfig {
		String token;
		String accountId;
		String baseUrl;
		String environment;
		String marketHubUrl;
		String userHubUrl;
		boolean liveContracts;
	}

	static class TopstepxContractInfo {
		String symbol;
		String contractId;
		String name;
		String symbolId;
		boolean active;
		boolean inferred;
		double tickSize;
		double tickValue;
	}

	static class TopstepxBarSnapshot {
		Instant timestamp;
		double open;
		double high;
		double low;
		double close;
		double volume;
		double vwap;
	}

	private static class MergeStats {
		private int existingRows;
		private int topstepRows;
		private int addedRows;
		private int replacedRows;
		private int overlapRows;
		private int driftRows;
		private int invalidRows;
		private int finalRows;
		private int contractsChecked;
		private int contractsWithBars;
		private String effectiveStart = "";
		private String effectiveEnd = "";
		private String first = "";
		private String last = "";
		private String backupPath = "";
	}

	public static void initializeStore() {
		try (Connection conn = DatabaseManager.getConnection();
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS FuturesConnections ("
					+ "provider TEXT PRIMARY KEY, "
					+ "enabled INTEGER, baseUrl TEXT, environment TEXT, username TEXT, apiKey TEXT, password TEXT, secret TEXT, "
					+ "appId TEXT, appVersion TEXT, cid TEXT, accountId TEXT, accountSpec TEXT, dataset TEXT, schema TEXT, symbols TEXT, "
					+ "marketHubUrl TEXT, userHubUrl TEXT, lastTestStatus TEXT, lastTestMessage TEXT, lastTestAt TEXT, updatedAt TEXT"
					+ ")"
			);
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS TopstepAccounts ("
					+ "accountId TEXT PRIMARY KEY, "
					+ "name TEXT NOT NULL, "
					+ "active INTEGER NOT NULL DEFAULT 0, "
					+ "createdAt TEXT, "
					+ "updatedAt TEXT"
					+ ")"
			);
		} catch (SQLException e) {
			e.printStackTrace();
		}

		ensureDefaultConnection(TRADOVATE);
		ensureDefaultConnection(TOPSTEPX);
		ensureConfiguredTopstepAccountListed();
	}

	public static String getConnectionsJson() {
		initializeStore();
		StringBuilder json = new StringBuilder("[");
		String[] providers = {TRADOVATE, TOPSTEPX};
		for (int index = 0; index < providers.length; index++) {
			if (index > 0) {
				json.append(",");
			}
			json.append(toJson(loadConnection(providers[index])));
		}
		json.append("]");
		return json.toString();
	}

	public static String getRequirementsJson() {
		return "["
			+ "{"
			+ "\"provider\":\"TRADOVATE\","
			+ "\"name\":\"Tradovate API\","
			+ "\"purpose\":\"Direct futures account, account list, and order routing when API access is enabled.\","
			+ "\"requiredFields\":[\"username\",\"password\",\"appId\",\"appVersion\",\"cid\",\"secret\",\"accountId\",\"accountSpec\"],"
			+ "\"defaultBaseUrl\":\"https://demo.tradovateapi.com/v1\","
			+ "\"docs\":\"https://partner.tradovate.com/api/rest-api-endpoints/authentication/access-token-request\""
			+ "},"
			+ "{"
			+ "\"provider\":\"TOPSTEPX\","
			+ "\"name\":\"TopstepX / ProjectX Gateway\","
			+ "\"purpose\":\"Funded-account compatible API path when TopstepX API access is subscribed and enabled.\","
			+ "\"requiredFields\":[\"username\",\"apiKey\",\"accountId\"],"
			+ "\"defaultBaseUrl\":\"https://api.topstepx.com/api\","
			+ "\"docs\":\"https://gateway.docs.projectx.com/docs/getting-started/authenticate/authenticate-api-key/\""
			+ "}"
			+ "]";
	}

	public static String getTopstepAccountsJson() {
		initializeStore();
		return topstepAccountsJson(loadTopstepAccounts());
	}

	public static String saveTopstepAccount(String name, String accountId, boolean activate) {
		initializeStore();
		String cleanAccountId = cleanTopstepAccountId(accountId);
		String cleanName = cleanOrDefault(name, "");
		if (isBlank(cleanAccountId)) {
			return "{\"success\":false,\"message\":\"Topstep account ID is required.\"}";
		}
		if (!cleanAccountId.matches("[0-9]+")) {
			return "{\"success\":false,\"message\":\"Topstep account ID must be numeric.\"}";
		}
		if (isBlank(cleanName)) {
			cleanName = "Topstep " + cleanAccountId;
		}

		String now = Instant.now().toString();
		String sql = "INSERT INTO TopstepAccounts (accountId, name, active, createdAt, updatedAt) "
			+ "VALUES (?, ?, ?, ?, ?) "
			+ "ON CONFLICT(accountId) DO UPDATE SET name = excluded.name, updatedAt = excluded.updatedAt";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, cleanAccountId);
			pstmt.setString(2, cleanName);
			pstmt.setInt(3, 0);
			pstmt.setString(4, now);
			pstmt.setString(5, now);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
			return "{\"success\":false,\"message\":" + jsonString("Failed to save Topstep account: " + safeMessage(e.getMessage())) + "}";
		}

		if (activate) {
			return activateTopstepAccount(cleanAccountId);
		}
		return "{"
			+ "\"success\":true,"
			+ "\"message\":\"Topstep account saved.\","
			+ "\"accountId\":" + jsonString(cleanAccountId) + ","
			+ "\"accounts\":" + getTopstepAccountsJson()
			+ "}";
	}

	public static String activateTopstepAccount(String accountId) {
		initializeStore();
		String cleanAccountId = cleanTopstepAccountId(accountId);
		if (isBlank(cleanAccountId)) {
			return "{\"success\":false,\"message\":\"Topstep account ID is required.\"}";
		}
		TopstepAccount account = findTopstepAccount(cleanAccountId);
		if (account == null) {
			return "{\"success\":false,\"message\":" + jsonString("Topstep account " + cleanAccountId + " is not saved yet.") + "}";
		}

		String now = Instant.now().toString();
		try (Connection conn = DatabaseManager.getConnection()) {
			try (PreparedStatement clear = conn.prepareStatement("UPDATE TopstepAccounts SET active = 0, updatedAt = ?")) {
				clear.setString(1, now);
				clear.executeUpdate();
			}
			try (PreparedStatement active = conn.prepareStatement("UPDATE TopstepAccounts SET active = 1, updatedAt = ? WHERE accountId = ?")) {
				active.setString(1, now);
				active.setString(2, cleanAccountId);
				active.executeUpdate();
			}
			try (PreparedStatement connection = conn.prepareStatement("UPDATE FuturesConnections SET accountId = ?, updatedAt = ? WHERE provider = ?")) {
				connection.setString(1, cleanAccountId);
				connection.setString(2, now);
				connection.setString(3, TOPSTEPX);
				connection.executeUpdate();
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return "{\"success\":false,\"message\":" + jsonString("Failed to activate Topstep account: " + safeMessage(e.getMessage())) + "}";
		}

		return "{"
			+ "\"success\":true,"
			+ "\"message\":" + jsonString("Topstep account " + account.name + " is now active for ProjectX.") + ","
			+ "\"accountId\":" + jsonString(cleanAccountId) + ","
			+ "\"accounts\":" + getTopstepAccountsJson() + ","
			+ "\"connection\":" + toJson(loadConnection(TOPSTEPX))
			+ "}";
	}

	public static String deleteTopstepAccount(String accountId) {
		initializeStore();
		String cleanAccountId = cleanTopstepAccountId(accountId);
		if (isBlank(cleanAccountId)) {
			return "{\"success\":false,\"message\":\"Topstep account ID is required.\"}";
		}
		TopstepAccount account = findTopstepAccount(cleanAccountId);
		if (account == null) {
			return "{\"success\":false,\"message\":" + jsonString("Topstep account " + cleanAccountId + " is not saved.") + "}";
		}

		ConnectionConfig config = loadConnection(TOPSTEPX);
		boolean clearConnectedAccount = account.active || cleanAccountId.equals(cleanTopstepAccountId(config.accountId));
		String now = Instant.now().toString();
		Connection conn = null;
		try {
			conn = DatabaseManager.getConnection();
			conn.setAutoCommit(false);
			try (PreparedStatement delete = conn.prepareStatement("DELETE FROM TopstepAccounts WHERE accountId = ?")) {
				delete.setString(1, cleanAccountId);
				int deleted = delete.executeUpdate();
				if (deleted == 0) {
					conn.rollback();
					return "{\"success\":false,\"message\":" + jsonString("Topstep account " + cleanAccountId + " is not saved.") + "}";
				}
			}
			if (clearConnectedAccount) {
				try (PreparedStatement clearAccounts = conn.prepareStatement("UPDATE TopstepAccounts SET active = 0, updatedAt = ?")) {
					clearAccounts.setString(1, now);
					clearAccounts.executeUpdate();
				}
				try (PreparedStatement clearConnection = conn.prepareStatement("UPDATE FuturesConnections SET accountId = '', updatedAt = ? WHERE provider = ?")) {
					clearConnection.setString(1, now);
					clearConnection.setString(2, TOPSTEPX);
					clearConnection.executeUpdate();
				}
			}
			conn.commit();
		} catch (SQLException e) {
			if (conn != null) {
				try {
					conn.rollback();
				} catch (SQLException rollbackError) {
					rollbackError.printStackTrace();
				}
			}
			e.printStackTrace();
			return "{\"success\":false,\"message\":" + jsonString("Failed to remove Topstep account: " + safeMessage(e.getMessage())) + "}";
		} finally {
			if (conn != null) {
				try {
					conn.setAutoCommit(true);
					conn.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		}

		return "{"
			+ "\"success\":true,"
			+ "\"message\":" + jsonString("Topstep account " + account.name + " was removed.") + ","
			+ "\"accountId\":" + jsonString(cleanAccountId) + ","
			+ "\"accounts\":" + getTopstepAccountsJson() + ","
			+ "\"connection\":" + toJson(loadConnection(TOPSTEPX))
			+ "}";
	}

	public static boolean saveConnection(
		String provider,
		boolean enabled,
		String baseUrl,
		String environment,
		String username,
		String apiKey,
		String password,
		String secret,
		String appId,
		String appVersion,
		String cid,
		String accountId,
		String accountSpec,
		String dataset,
		String schema,
		String symbols,
		String marketHubUrl,
		String userHubUrl
	) {
		initializeStore();
		String normalizedProvider = normalizeProvider(provider);
		ConnectionConfig existing = loadConnection(normalizedProvider);
		ConnectionConfig config = defaultConnection(normalizedProvider);
		config.enabled = enabled;
		config.baseUrl = cleanOrDefault(baseUrl, existing.baseUrl);
		config.environment = cleanOrDefault(environment, existing.environment);
		config.username = cleanOrDefault(username, existing.username);
		config.apiKey = secretOrExisting(apiKey, existing.apiKey);
		config.password = secretOrExisting(password, existing.password);
		config.secret = secretOrExisting(secret, existing.secret);
		config.appId = cleanOrDefault(appId, existing.appId);
		config.appVersion = cleanOrDefault(appVersion, existing.appVersion);
		config.cid = cleanOrDefault(cid, existing.cid);
		config.accountId = cleanOrDefault(accountId, existing.accountId);
		config.accountSpec = cleanOrDefault(accountSpec, existing.accountSpec);
		config.dataset = cleanOrDefault(dataset, existing.dataset);
		config.schema = cleanOrDefault(schema, existing.schema);
		config.symbols = cleanOrDefault(symbols, existing.symbols);
		config.marketHubUrl = cleanOrDefault(marketHubUrl, existing.marketHubUrl);
		config.userHubUrl = cleanOrDefault(userHubUrl, existing.userHubUrl);
		config.lastTestStatus = existing.lastTestStatus;
		config.lastTestMessage = existing.lastTestMessage;
		config.lastTestAt = existing.lastTestAt;
		config.updatedAt = Instant.now().toString();

		String sql = "INSERT OR REPLACE INTO FuturesConnections (provider, enabled, baseUrl, environment, username, apiKey, password, secret, appId, appVersion, cid, accountId, accountSpec, dataset, schema, symbols, marketHubUrl, userHubUrl, lastTestStatus, lastTestMessage, lastTestAt, updatedAt) "
			+ "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			bindConnection(pstmt, config);
			return pstmt.executeUpdate() > 0;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static String testConnection(String provider) {
		initializeStore();
		String normalizedProvider = normalizeProvider(provider);
		ConnectionConfig config = loadConnection(normalizedProvider);
		String status = "failed";
		String message;

		try {
			if (TRADOVATE.equals(normalizedProvider)) {
				message = testTradovate(config);
			} else if (TOPSTEPX.equals(normalizedProvider)) {
				message = testTopstepx(config);
			} else {
				message = "Unsupported futures provider.";
			}
			status = message.toLowerCase().contains("connected") || message.toLowerCase().contains("authenticated") ? "connected" : "failed";
		} catch (Exception e) {
			message = "Connection test failed: " + safeMessage(e.getMessage());
		}

		saveTestResult(normalizedProvider, status, message);
		return "{\"provider\":" + jsonString(normalizedProvider)
			+ ",\"status\":" + jsonString(status)
			+ ",\"message\":" + jsonString(message)
			+ ",\"connection\":" + toJson(loadConnection(normalizedProvider))
			+ "}";
	}

	public static String updateBacktestData(String symbols, String startDate, String endDate, String requestedSchema) {
		return FuturesMarketDataStore.refreshBacktestMarketData(symbols, startDate, endDate, 1);
	}

	public static String rebuildDerivedFuturesData(String symbol) {
		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		File source = new File(futuresDataDir() + "/1min/" + normalizedSymbol + ".csv");
		if (!source.exists()) {
			return "{\"success\":false,\"message\":"
				+ jsonString("No 1-minute futures file exists for " + normalizedSymbol + ". Run TopstepX historical gap fill or live capture first.")
				+ ",\"rows\":0}";
		}

		try {
			StringBuilder csv = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new FileReader(source))) {
				String line;
				while ((line = reader.readLine()) != null) {
					csv.append(line).append("\n");
				}
			}
			int rows = rewriteInternalFuturesCsv(normalizedSymbol, csv.toString());
			return "{\"success\":true,\"message\":"
				+ jsonString("Rebuilt " + normalizedSymbol + " futures data into enriched 1-minute, 5-minute, 15-minute, 1-hour, and derived Level 2 gap-fill files.")
				+ ",\"symbol\":" + jsonString(normalizedSymbol)
				+ ",\"rows\":" + rows
				+ ",\"path\":" + jsonString(futuresDataDir() + "/1min/" + normalizedSymbol + ".csv")
				+ ",\"syntheticLevel2Path\":" + jsonString(futuresDataDir() + "/" + SYNTHETIC_LEVEL2_FOLDER + "/" + normalizedSymbol + ".csv")
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("Derived futures rebuild failed: " + safeMessage(e.getMessage())) + ",\"rows\":0}";
		}
	}

	public static String importTopstepxBars(String symbols, String startDate, String endDate) {
		return importTopstepxBars(symbols, startDate, endDate, 1);
	}

	public static String importTopstepxBars(String symbols, String startDate, String endDate, int maxContractsPerSymbol) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\",\"symbols\":[]}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\",\"symbols\":[]}";
		}

		LocalDate requestedStart = parseDate(startDate, LocalDate.now(ZoneOffset.UTC).minusYears(1));
		LocalDate requestedEnd = parseDate(endDate, LocalDate.now(ZoneOffset.UTC));
		if (requestedEnd.isBefore(requestedStart)) {
			LocalDate swap = requestedStart;
			requestedStart = requestedEnd;
			requestedEnd = swap;
		}

		List<String> symbolList = normalizeSymbolList(symbols);
		String baseUrl = cleanOrDefault(config.baseUrl, "https://api.topstepx.com/api");
		boolean liveContracts = "LIVE".equalsIgnoreCase(config.environment);
		int contractLimit = Math.max(1, Math.min(20, maxContractsPerSymbol));
		String runId = "topstepx_merge_" + Instant.now().toString().replace(":", "").replace(".", "_");
		StringBuilder symbolResults = new StringBuilder("[");
		boolean success = true;

		try {
			String token = topstepxSessionToken(config);
			HttpResult validate = postJson(baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;

			for (int index = 0; index < symbolList.size(); index++) {
				String symbol = symbolList.get(index);
				if (index > 0) {
					symbolResults.append(",");
				}
				try {
					MergeStats stats = importTopstepxBarsForSymbol(
						config,
						baseUrl,
						activeToken,
						liveContracts,
						symbol,
						requestedStart,
						requestedEnd,
						runId,
						contractLimit
					);
					symbolResults.append("{")
						.append("\"symbol\":").append(jsonString(symbol)).append(",")
						.append("\"success\":true,")
						.append("\"existingRows\":").append(stats.existingRows).append(",")
						.append("\"topstepRows\":").append(stats.topstepRows).append(",")
						.append("\"addedRows\":").append(stats.addedRows).append(",")
						.append("\"overlapRows\":").append(stats.overlapRows).append(",")
						.append("\"driftRows\":").append(stats.driftRows).append(",")
						.append("\"invalidRows\":").append(stats.invalidRows).append(",")
						.append("\"finalRows\":").append(stats.finalRows).append(",")
						.append("\"contractsChecked\":").append(stats.contractsChecked).append(",")
						.append("\"contractsWithBars\":").append(stats.contractsWithBars).append(",")
						.append("\"first\":").append(jsonString(stats.first)).append(",")
						.append("\"last\":").append(jsonString(stats.last)).append(",")
						.append("\"backupPath\":").append(jsonString(stats.backupPath))
						.append("}");
				} catch (Exception symbolError) {
					success = false;
					symbolResults.append("{")
						.append("\"symbol\":").append(jsonString(symbol)).append(",")
						.append("\"success\":false,")
						.append("\"message\":").append(jsonString(safeMessage(symbolError.getMessage())))
						.append("}");
				}
			}
			symbolResults.append("]");
			String message = "TopstepX historical merge completed for " + symbolList.size() + " symbol(s). Existing native/live rows were preserved on overlapping timestamps; Topstep filled missing timestamps only.";
			saveTestResult(TOPSTEPX, success ? "connected" : "failed", message);
			return "{"
				+ "\"success\":" + success + ","
				+ "\"message\":" + jsonString(message) + ","
				+ "\"provider\":\"TOPSTEPX\","
				+ "\"startDate\":" + jsonString(requestedStart.toString()) + ","
				+ "\"endDate\":" + jsonString(requestedEnd.toString()) + ","
				+ "\"liveContracts\":" + liveContracts + ","
				+ "\"mergeMode\":\"missing-only\","
				+ "\"maxContractsPerSymbol\":" + contractLimit + ","
				+ "\"runId\":" + jsonString(runId) + ","
				+ "\"symbols\":" + symbolResults
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX historical import failed: " + safeMessage(e.getMessage())) + ",\"symbols\":[]}";
		}
	}

	public static boolean isExecutionProviderReady(String provider) {
		ConnectionConfig config = loadConnection(provider);
		return config.enabled
			&& "connected".equals(config.lastTestStatus)
			&& (TRADOVATE.equals(config.provider) || TOPSTEPX.equals(config.provider));
	}

	public static String getTopstepxConfiguredAccountId() {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		return cleanOrDefault(config.accountId, "");
	}

	public static String refreshTopstepxAccountForStart(String accountId) {
		initializeStore();
		String cleanAccountId = cleanTopstepAccountId(accountId);
		if (isBlank(cleanAccountId)) {
			return "";
		}
		TopstepAccount savedAccount = findTopstepAccount(cleanAccountId);
		if (savedAccount == null || !topstepxSavedAccountCanRefresh(savedAccount.name)) {
			return cleanAccountId;
		}
		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			HttpResult accounts = postJson(realtimeConfig.baseUrl + "/Account/search", "{\"onlyActiveAccounts\":true}", activeToken);
			if (accounts.statusCode < 200 || accounts.statusCode >= 300) {
				return cleanAccountId;
			}
			List<String> accountObjects = extractJsonArrayObjects(accounts.body, "accounts");
			if (!isBlank(findAccountObject(accountObjects, cleanAccountId))) {
				return cleanAccountId;
			}
			String replacementAccountId = topstepxReplacementAccountId(accountObjects, savedAccount.name);
			if (isBlank(replacementAccountId) || cleanAccountId.equals(replacementAccountId)) {
				return cleanAccountId;
			}
			replaceSavedTopstepAccountId(cleanAccountId, replacementAccountId, savedAccount.name);
			return replacementAccountId;
		} catch (Exception e) {
			return cleanAccountId;
		}
	}

	static TopstepxRealtimeConfig createTopstepxRealtimeConfig() throws Exception {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		if (!config.enabled) {
			throw new IllegalStateException("TopstepX connection is disabled.");
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			throw new IllegalStateException("TopstepX username/API key are missing.");
		}
		if (isBlank(config.accountId)) {
			throw new IllegalStateException("TopstepX account ID is missing.");
		}

		TopstepxRealtimeConfig realtimeConfig = new TopstepxRealtimeConfig();
		realtimeConfig.token = topstepxSessionToken(config);
		realtimeConfig.accountId = config.accountId.trim();
		realtimeConfig.baseUrl = cleanOrDefault(config.baseUrl, "https://api.topstepx.com/api");
		realtimeConfig.environment = cleanOrDefault(config.environment, "");
		realtimeConfig.marketHubUrl = cleanOrDefault(config.marketHubUrl, "https://rtc.topstepx.com/hubs/market");
		realtimeConfig.userHubUrl = cleanOrDefault(config.userHubUrl, "https://rtc.topstepx.com/hubs/user");
		realtimeConfig.liveContracts = "LIVE".equalsIgnoreCase(config.environment);
		return realtimeConfig;
	}

	static List<TopstepxContractInfo> resolveTopstepxRealtimeContracts(
		TopstepxRealtimeConfig realtimeConfig,
		String symbols
	) throws Exception {
		List<String> symbolList = normalizeSymbolList(symbols);
		List<TopstepxContractInfo> results = new ArrayList<TopstepxContractInfo>();
		LocalDate startDate = LocalDate.now(ZoneOffset.UTC).minusDays(30);
		LocalDate endDate = LocalDate.now(ZoneOffset.UTC).plusMonths(3);
		for (int index = 0; index < symbolList.size(); index++) {
			String symbol = symbolList.get(index);
			List<TopstepContract> contracts = topstepxContractsForSymbol(
				realtimeConfig.baseUrl,
				realtimeConfig.token,
				realtimeConfig.liveContracts,
				symbol,
				1,
				startDate,
				endDate
			);
			if (contracts.isEmpty()) {
				continue;
			}
			TopstepContract contract = contracts.get(0);
			TopstepxContractInfo info = new TopstepxContractInfo();
			info.symbol = symbol;
			info.contractId = contract.id;
			info.name = contract.name;
			info.symbolId = contract.symbolId;
			info.active = contract.active;
			info.inferred = contract.inferred;
			info.tickSize = contract.tickSize;
			info.tickValue = contract.tickValue;
			results.add(info);
		}
		return results;
	}

	static List<TopstepxBarSnapshot> fetchTopstepxRecentMinuteBars(
		String symbol,
		int lookbackMinutes,
		boolean includePartialBar
	) throws Exception {
		TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
		HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", realtimeConfig.token);
		String refreshedToken = extractJsonString(validate.body, "newToken");
		String activeToken = isBlank(refreshedToken) ? realtimeConfig.token : refreshedToken;
		realtimeConfig.token = activeToken;

		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		List<TopstepxContractInfo> contracts = resolveTopstepxRealtimeContracts(realtimeConfig, normalizedSymbol);
		if (contracts.isEmpty()) {
			return new ArrayList<TopstepxBarSnapshot>();
		}
		TopstepxContractInfo contract = contracts.get(0);
		Instant end = Instant.now();
		Instant start = end.minusSeconds((long) Math.max(15, lookbackMinutes) * 60L);
		String body = "{"
			+ "\"contractId\":" + jsonString(contract.contractId) + ","
			+ "\"live\":" + realtimeConfig.liveContracts + ","
			+ "\"startTime\":" + jsonString(start.toString()) + ","
			+ "\"endTime\":" + jsonString(end.toString()) + ","
			+ "\"unit\":2,"
			+ "\"unitNumber\":1,"
			+ "\"limit\":20000,"
			+ "\"includePartialBar\":" + includePartialBar
			+ "}";
		HttpResult result = postJson(realtimeConfig.baseUrl + "/History/retrieveBars", body, activeToken);
		if (result.statusCode == 429) {
			Thread.sleep(2000L);
			result = postJson(realtimeConfig.baseUrl + "/History/retrieveBars", body, activeToken);
		}
		if (result.statusCode < 200 || result.statusCode >= 300) {
			throw new IllegalStateException("recent ProjectX history pull failed for " + normalizedSymbol + " (" + result.statusCode + "): " + summarizeBody(result.body));
		}

		List<TopstepxBarSnapshot> snapshots = new ArrayList<TopstepxBarSnapshot>();
		List<String> objects = extractJsonArrayObjects(result.body, "bars");
		for (int index = 0; index < objects.size(); index++) {
			InternalBar bar = topstepBarFromJson(objects.get(index));
			if (bar == null || bar.timestamp == null || bar.close <= 0.0) {
				continue;
			}
			TopstepxBarSnapshot snapshot = new TopstepxBarSnapshot();
			snapshot.timestamp = bar.timestamp;
			snapshot.open = bar.open;
			snapshot.high = bar.high;
			snapshot.low = bar.low;
			snapshot.close = bar.close;
			snapshot.volume = bar.volume;
			snapshot.vwap = bar.vwap;
			snapshots.add(snapshot);
		}
		Collections.sort(snapshots, new Comparator<TopstepxBarSnapshot>() {
			@Override
			public int compare(TopstepxBarSnapshot first, TopstepxBarSnapshot second) {
				return first.timestamp.compareTo(second.timestamp);
			}
		});
		return snapshots;
	}

	static String projectxSymbolIdForRealtime(String symbol) {
		return projectxSymbolId(symbol);
	}

	public static String syncTopstepxReadOnly() {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\"}";
		}
		if (isBlank(config.accountId)) {
			return "{\"success\":false,\"message\":\"TopstepX account ID is missing.\"}";
		}

		String baseUrl = cleanOrDefault(config.baseUrl, "https://api.topstepx.com/api");
		boolean liveContracts = "LIVE".equalsIgnoreCase(config.environment);
		String accountId = config.accountId.trim();
		String startedAt = Instant.now().toString();
		try {
			String token = topstepxSessionToken(config);
			HttpResult validate = postJson(baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			HttpResult accounts = postJson(baseUrl + "/Account/search", "{\"onlyActiveAccounts\":true}", activeToken);
			HttpResult openPositions = postJson(baseUrl + "/Position/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			HttpResult openOrders = postJson(baseUrl + "/Order/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			String startTimestamp = Instant.now().minusSeconds(7L * 24L * 60L * 60L).toString();
			String endTimestamp = Instant.now().toString();
			String tradeBody = "{"
				+ "\"accountId\":" + accountId + ","
				+ "\"startTimestamp\":" + jsonString(startTimestamp) + ","
				+ "\"endTimestamp\":" + jsonString(endTimestamp)
				+ "}";
			HttpResult trades = postJson(baseUrl + "/Trade/search", tradeBody, activeToken);

			StringBuilder contractsJson = new StringBuilder("{");
			String[] symbols = {"MNQ", "NQ", "M2K", "MYM", "MGC", "MCL", "ES", "MES", "GC"};
			for (int index = 0; index < symbols.length; index++) {
				if (index > 0) {
					contractsJson.append(",");
				}
				String body = "{\"live\":" + liveContracts + ",\"searchText\":" + jsonString(symbols[index]) + "}";
				HttpResult contracts = postJson(baseUrl + "/Contract/search", body, activeToken);
				contractsJson.append(jsonString(symbols[index])).append(":")
					.append(syncResponseJson(contracts, "contracts"));
			}
			contractsJson.append("}");

			return "{"
				+ "\"success\":true,"
				+ "\"message\":\"TopstepX read-only sync completed.\","
				+ "\"provider\":\"TOPSTEPX\","
				+ "\"environment\":" + jsonString(config.environment) + ","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"liveContracts\":" + liveContracts + ","
				+ "\"startedAt\":" + jsonString(startedAt) + ","
				+ "\"syncedAt\":" + jsonString(Instant.now().toString()) + ","
				+ "\"counts\":{"
					+ "\"sessionValid\":" + (validate.statusCode >= 200 && validate.statusCode < 300 ? 1 : 0) + ","
					+ "\"accounts\":" + jsonArrayObjectCount(accounts.body, "accounts") + ","
					+ "\"openPositions\":" + jsonArrayObjectCount(openPositions.body, "positions") + ","
					+ "\"openOrders\":" + jsonArrayObjectCount(openOrders.body, "orders") + ","
					+ "\"recentTrades\":" + jsonArrayObjectCount(trades.body, "trades")
				+ "},"
				+ "\"session\":" + syncResponseJson(validate, "session") + ","
				+ "\"accounts\":" + syncResponseJson(accounts, "accounts") + ","
				+ "\"contracts\":" + contractsJson + ","
				+ "\"openPositions\":" + syncResponseJson(openPositions, "positions") + ","
				+ "\"openOrders\":" + syncResponseJson(openOrders, "orders") + ","
				+ "\"recentTrades\":" + syncResponseJson(trades, "trades")
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX read-only sync failed: " + safeMessage(e.getMessage())) + "}";
		}
	}

	public static String getTopstepxAccountMetrics(
		String requiredAccountId,
		String symbols,
		double fallbackAccountSize,
		int lookbackDays
	) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanOrDefault(requiredAccountId, "");
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\",\"source\":\"TOPSTEPX\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\",\"source\":\"TOPSTEPX\"}";
		}
		if (isBlank(config.accountId) || !accountId.equals(config.accountId.trim())) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before broker metrics can be trusted.") + ",\"source\":\"TOPSTEPX\"}";
		}

		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			if (!accountId.equals(realtimeConfig.accountId)) {
				return "{\"success\":false,\"message\":" + jsonString("ProjectX authenticated account is not " + accountId + ".") + ",\"source\":\"TOPSTEPX\"}";
			}
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;

			HttpResult accounts = postJson(realtimeConfig.baseUrl + "/Account/search", "{\"onlyActiveAccounts\":true}", activeToken);
			if (accounts.statusCode < 200 || accounts.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX account search failed: " + topstepxErrorSummary(accounts.body)) + ",\"source\":\"TOPSTEPX\",\"statusCode\":" + accounts.statusCode + "}";
			}
			String accountObject = findAccountObject(extractJsonArrayObjects(accounts.body, "accounts"), accountId);
			if (isBlank(accountObject)) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX account " + accountId + " was not returned by Account/search.") + ",\"source\":\"TOPSTEPX\"}";
			}
			String accountName = extractJsonString(accountObject, "name");

			Map<String, String> symbolByContractId = new HashMap<String, String>();
			List<TopstepxContractInfo> contracts = resolveTopstepxRealtimeContracts(realtimeConfig, symbols);
			for (int index = 0; index < contracts.size(); index++) {
				TopstepxContractInfo contract = contracts.get(index);
				if (contract != null && !isBlank(contract.contractId)) {
					symbolByContractId.put(contract.contractId, normalizeFuturesSymbol(contract.symbol));
				}
			}

			HttpResult openPositions = postJson(realtimeConfig.baseUrl + "/Position/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openPositions.statusCode < 200 || openPositions.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-position search failed: " + topstepxErrorSummary(openPositions.body)) + ",\"source\":\"TOPSTEPX\",\"statusCode\":" + openPositions.statusCode + "}";
			}
			HttpResult openOrders = postJson(realtimeConfig.baseUrl + "/Order/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openOrders.statusCode < 200 || openOrders.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-order search failed: " + topstepxErrorSummary(openOrders.body)) + ",\"source\":\"TOPSTEPX\",\"statusCode\":" + openOrders.statusCode + "}";
			}

			int safeLookbackDays = Math.max(1, Math.min(3650, lookbackDays));
			Instant endTimestamp = Instant.now();
			Instant startTimestamp = endTimestamp.minusSeconds((long) safeLookbackDays * 24L * 60L * 60L);
			String tradeBody = "{"
				+ "\"accountId\":" + accountId + ","
				+ "\"startTimestamp\":" + jsonString(startTimestamp.toString()) + ","
				+ "\"endTimestamp\":" + jsonString(endTimestamp.toString())
				+ "}";
			HttpResult trades = postJson(realtimeConfig.baseUrl + "/Trade/search", tradeBody, activeToken);
			if (trades.statusCode < 200 || trades.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX trade search failed: " + topstepxErrorSummary(trades.body)) + ",\"source\":\"TOPSTEPX\",\"statusCode\":" + trades.statusCode + "}";
			}

			double brokerBalance = firstJsonNumber(accountObject, "balance", "currentBalance", "cashBalance", "equity");
			double brokerAccountSize = firstJsonNumber(accountObject, "accountSize", "startingBalance", "initialBalance", "startBalance", "fundingAmount", "size");
			boolean balanceTracksPnl = topstepxAccountBalanceTracksPnl(accountName);
			double accountSize = topstepxAccountSizeFromBalance(accountName, brokerBalance, brokerAccountSize, fallbackAccountSize);
			String accountSizeSource = !Double.isNaN(brokerAccountSize) && brokerAccountSize > 0.0
				? "TOPSTEPX_ACCOUNT"
				: (!balanceTracksPnl && accountSize > 0.0 && fallbackAccountSize > 0.0 && Math.abs(accountSize - fallbackAccountSize) > 0.01 ? "TOPSTEPX_BALANCE_INFERRED" : "FALLBACK");
			if (accountSize <= 0.0) {
				accountSize = fallbackAccountSize > 0.0 ? fallbackAccountSize : brokerBalance;
				accountSizeSource = fallbackAccountSize > 0.0 ? "FALLBACK" : "TOPSTEPX_BALANCE";
			}
			double accountDrawdown = firstJsonNumber(accountObject, "drawdown", "maxDrawdown", "currentDrawdown");
			double realizedPnl = topstepxAccountPnlFromBalance(accountName, brokerBalance, accountSize);
			double riskCurrentBalance = topstepxRiskBalanceFromProjectxBalance(accountName, brokerBalance, accountSize);
			double brokerDrawdown = Double.isNaN(accountDrawdown) ? Math.max(0.0, accountSize - riskCurrentBalance) : Math.abs(accountDrawdown);

			List<String> positionObjects = extractJsonArrayObjects(openPositions.body, "positions");
			StringBuilder positionsJson = new StringBuilder("[");
			int openContracts = 0;
			for (int index = 0; index < positionObjects.size(); index++) {
				String position = positionObjects.get(index);
				if (index > 0) {
					positionsJson.append(",");
				}
				String contractId = firstNonBlank(
					extractJsonString(position, "contractId"),
					extractJsonString(position, "contractID"),
					extractJsonString(position, "contract")
				);
				String symbol = exposureSymbolFromJson(position, symbolByContractId);
				int size = exposureSizeFromJson(position);
				openContracts += size;
				double averagePrice = firstJsonNumber(position, "averagePrice", "avgPrice", "entryPrice", "price");
				double positionPnl = firstJsonNumber(position, "profitAndLoss", "unrealizedPnl", "openPnl", "pnl");
				positionsJson.append("{")
					.append("\"id\":").append(jsonNumberOrString(position, "id")).append(",")
					.append("\"accountId\":").append(jsonString(accountId)).append(",")
					.append("\"contractId\":").append(jsonString(contractId)).append(",")
					.append("\"symbol\":").append(jsonString(symbol)).append(",")
					.append("\"side\":").append(jsonString(topstepxPositionSide(position))).append(",")
					.append("\"contracts\":").append(size).append(",")
					.append("\"averagePrice\":").append(numberOrZero(averagePrice)).append(",")
					.append("\"entryPrice\":").append(numberOrZero(averagePrice)).append(",")
					.append("\"pnl\":").append(numberOrZero(positionPnl)).append(",")
					.append("\"createdAt\":").append(jsonString(extractJsonString(position, "creationTimestamp")))
					.append("}");
			}
			positionsJson.append("]");

			List<String> orderObjects = extractJsonArrayObjects(openOrders.body, "orders");
			StringBuilder ordersJson = new StringBuilder("[");
			for (int index = 0; index < orderObjects.size(); index++) {
				String order = orderObjects.get(index);
				if (index > 0) {
					ordersJson.append(",");
				}
				String contractId = firstNonBlank(
					extractJsonString(order, "contractId"),
					extractJsonString(order, "contractID"),
					extractJsonString(order, "contract")
				);
				String symbol = exposureSymbolFromJson(order, symbolByContractId);
				ordersJson.append("{")
					.append("\"id\":").append(jsonNumberOrString(order, "id")).append(",")
					.append("\"accountId\":").append(jsonString(accountId)).append(",")
					.append("\"contractId\":").append(jsonString(contractId)).append(",")
					.append("\"symbol\":").append(jsonString(symbol)).append(",")
					.append("\"side\":").append(jsonString(topstepxTradeSide(order))).append(",")
					.append("\"status\":").append(jsonString(topstepxOrderStatus(order))).append(",")
					.append("\"rawStatus\":").append(jsonNumberOrString(order, "status")).append(",")
					.append("\"orderType\":").append(jsonString(topstepxOrderType(order))).append(",")
					.append("\"contracts\":").append(exposureSizeFromJson(order)).append(",")
					.append("\"price\":").append(numberOrZero(firstJsonNumber(order, "price", "limitPrice", "stopPrice"))).append(",")
					.append("\"customTag\":").append(jsonString(firstNonBlank(extractJsonString(order, "customTag"), extractJsonString(order, "tag"), extractJsonString(order, "text")))).append(",")
					.append("\"createdAt\":").append(jsonString(extractJsonString(order, "creationTimestamp")))
					.append("}");
			}
			ordersJson.append("]");

			List<String> tradeObjects = extractJsonArrayObjects(trades.body, "trades");
			StringBuilder tradesJson = new StringBuilder("[");
			double closedTradePnl = 0.0;
			double totalFees = topstepxTotalTradeCosts(tradeObjects);
			int closedTrades = 0;
			for (int index = 0; index < tradeObjects.size(); index++) {
				String trade = tradeObjects.get(index);
				double grossPnl = firstJsonNumber(trade, "profitAndLoss");
				double brokerFees = firstJsonNumber(trade, "fees", "fee");
				double commission = firstJsonNumber(trade, "commission", "commissions");
				double fees = topstepxTradeCost(trade);
				boolean closed = !Double.isNaN(grossPnl);
				boolean voided = jsonBoolean(trade, "voided");
				double netPnl = closed ? grossPnl - (Double.isNaN(fees) ? 0.0 : fees) : 0.0;
				if (closed && !voided) {
					closedTrades++;
					closedTradePnl += netPnl;
				}
				if (index > 0) {
					tradesJson.append(",");
				}
				String contractId = firstNonBlank(
					extractJsonString(trade, "contractId"),
					extractJsonString(trade, "contractID"),
					extractJsonString(trade, "contract")
				);
				String symbol = exposureSymbolFromJson(trade, symbolByContractId);
				tradesJson.append("{")
					.append("\"id\":").append(jsonNumberOrString(trade, "id")).append(",")
					.append("\"accountId\":").append(jsonString(accountId)).append(",")
					.append("\"contractId\":").append(jsonString(contractId)).append(",")
					.append("\"symbol\":").append(jsonString(symbol)).append(",")
					.append("\"side\":").append(jsonString(topstepxTradeSide(trade))).append(",")
					.append("\"contracts\":").append(exposureSizeFromJson(trade)).append(",")
					.append("\"price\":").append(numberOrZero(firstJsonNumber(trade, "price"))).append(",")
					.append("\"entryPrice\":").append(numberOrZero(firstJsonNumber(trade, "price"))).append(",")
					.append("\"exitPrice\":").append(closed ? numberOrZero(firstJsonNumber(trade, "price")) : "0").append(",")
					.append("\"grossPnl\":").append(numberOrZero(grossPnl)).append(",")
					.append("\"fees\":").append(numberOrZero(fees)).append(",")
					.append("\"brokerFees\":").append(numberOrZero(brokerFees)).append(",")
					.append("\"commission\":").append(numberOrZero(commission)).append(",")
					.append("\"totalFees\":").append(numberOrZero(fees)).append(",")
					.append("\"pnl\":").append(numberOrZero(netPnl)).append(",")
					.append("\"closed\":").append(closed && !voided).append(",")
					.append("\"voided\":").append(voided).append(",")
					.append("\"orderId\":").append(jsonNumberOrString(trade, "orderId")).append(",")
					.append("\"createdAt\":").append(jsonString(extractJsonString(trade, "creationTimestamp")))
					.append("}");
			}
			tradesJson.append("]");
			double returnPct = accountSize <= 0.0 ? 0.0 : (realizedPnl / accountSize) * 100.0;

			return "{"
				+ "\"success\":true,"
				+ "\"source\":\"TOPSTEPX\","
				+ "\"transport\":\"topstepx-broker-metrics-v1\","
				+ "\"authoritative\":true,"
				+ "\"message\":\"TopstepX broker metrics synced.\","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"accountName\":" + jsonString(accountName) + ","
				+ "\"canTrade\":" + jsonBoolean(accountObject, "canTrade") + ","
				+ "\"balanceMode\":" + jsonString(balanceTracksPnl ? "PNL" : "EQUITY") + ","
				+ "\"balanceTracksPnl\":" + balanceTracksPnl + ","
				+ "\"balance\":" + numberOrZero(brokerBalance) + ","
					+ "\"cashBalance\":" + numberOrZero(brokerBalance) + ","
					+ "\"accountSize\":" + numberOrZero(accountSize) + ","
					+ "\"accountSizeSource\":" + jsonString(accountSizeSource) + ","
				+ "\"realizedPnl\":" + numberOrZero(realizedPnl) + ","
				+ "\"closedTradePnl\":" + numberOrZero(closedTradePnl) + ","
				+ "\"totalFees\":" + numberOrZero(totalFees) + ","
				+ "\"unrealizedPnl\":0.0,"
				+ "\"currentBalance\":" + numberOrZero(brokerBalance) + ","
				+ "\"riskCurrentBalance\":" + numberOrZero(riskCurrentBalance) + ","
				+ "\"equityBalance\":" + numberOrZero(riskCurrentBalance) + ","
				+ "\"currentPnl\":" + numberOrZero(realizedPnl) + ","
				+ "\"returnPct\":" + numberOrZero(returnPct) + ","
				+ "\"drawdown\":" + numberOrZero(brokerDrawdown) + ","
				+ "\"numberOfTrades\":" + closedTrades + ","
				+ "\"openTrades\":" + positionObjects.size() + ","
				+ "\"openContracts\":" + openContracts + ","
				+ "\"openOrders\":" + orderObjects.size() + ","
				+ "\"tradeHistoryDays\":" + safeLookbackDays + ","
				+ "\"startedAt\":" + jsonString(startTimestamp.toString()) + ","
				+ "\"syncedAt\":" + jsonString(endTimestamp.toString()) + ","
				+ "\"positions\":" + positionsJson + ","
				+ "\"orders\":" + ordersJson + ","
				+ "\"trades\":" + tradesJson
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX broker metrics sync failed: " + safeMessage(e.getMessage())) + ",\"source\":\"TOPSTEPX\"}";
		}
	}

	static boolean topstepxAccountBalanceTracksPnl(String accountName) {
		String cleanName = cleanOrDefault(accountName, "").toUpperCase(Locale.US);
		return cleanName.contains("EXPRESS") || cleanName.contains("FUNDED");
	}

	static double topstepxAccountPnlFromBalance(String accountName, double projectxBalance, double accountSize) {
		if (Double.isNaN(projectxBalance)) {
			return Double.NaN;
		}
		if (topstepxAccountBalanceTracksPnl(accountName)) {
			return projectxBalance;
		}
		return accountSize > 0.0 ? projectxBalance - accountSize : projectxBalance;
	}

	static double topstepxRiskBalanceFromProjectxBalance(String accountName, double projectxBalance, double accountSize) {
		if (Double.isNaN(projectxBalance)) {
			return Double.NaN;
		}
		if (!topstepxAccountBalanceTracksPnl(accountName)) {
			return projectxBalance;
		}
		return accountSize > 0.0 ? Math.max(0.0, accountSize + projectxBalance) : Math.max(0.0, projectxBalance);
	}

	static double topstepxAccountSizeFromBalance(String accountName, double projectxBalance, double brokerAccountSize, double fallbackAccountSize) {
		if (!Double.isNaN(brokerAccountSize) && brokerAccountSize > 0.0) {
			return brokerAccountSize;
		}
		double fallback = fallbackAccountSize > 0.0 ? fallbackAccountSize : 0.0;
		if (topstepxAccountBalanceTracksPnl(accountName)) {
			return fallback;
		}
		double inferred = topstepxStandardEquityAccountSize(projectxBalance);
		if (inferred > 0.0 && (fallback <= 0.0 || Math.abs(inferred - fallback) > 0.01)) {
			return inferred;
		}
		return fallback > 0.0 ? fallback : projectxBalance;
	}

	private static double topstepxStandardEquityAccountSize(double projectxBalance) {
		if (Double.isNaN(projectxBalance) || projectxBalance <= 0.0) {
			return 0.0;
		}
		double[] sizes = new double[] { 50000.0, 100000.0, 150000.0 };
		double bestSize = 0.0;
		double bestDistance = Double.MAX_VALUE;
		for (int index = 0; index < sizes.length; index++) {
			double size = sizes[index];
			double distance = Math.abs(projectxBalance - size);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestSize = size;
			}
		}
		return bestDistance <= 30000.0 ? bestSize : 0.0;
	}

	static String topstepxReplacementAccountId(List<String> accountObjects, String savedAccountName) {
		if (!topstepxSavedAccountCanRefresh(savedAccountName) || accountObjects == null || accountObjects.isEmpty()) {
			return "";
		}
		double desiredSize = topstepxDesiredSizeFromSavedName(savedAccountName);
		String bestAccountId = "";
		double bestDistance = Double.MAX_VALUE;
		for (int index = 0; index < accountObjects.size(); index++) {
			String object = accountObjects.get(index);
			String name = extractJsonString(object, "name").toUpperCase(Locale.US);
			if (!name.startsWith("PRAC") || !jsonBoolean(object, "canTrade")) {
				continue;
			}
			double balance = firstJsonNumber(object, "balance", "currentBalance", "cashBalance", "equity");
			double inferredSize = topstepxStandardEquityAccountSize(balance);
			double distance = desiredSize > 0.0 && inferredSize > 0.0 ? Math.abs(inferredSize - desiredSize) : 0.0;
			if (desiredSize > 0.0 && inferredSize > 0.0 && distance > 0.01) {
				continue;
			}
			if (distance < bestDistance) {
				bestDistance = distance;
				bestAccountId = cleanOrDefault(extractJsonNumber(object, "id"), extractJsonString(object, "id"));
			}
		}
		return bestAccountId;
	}

	private static boolean topstepxSavedAccountCanRefresh(String savedAccountName) {
		String cleanName = cleanOrDefault(savedAccountName, "").toUpperCase(Locale.US);
		return cleanName.contains("PRACTICE") || cleanName.contains("PRAC");
	}

	private static double topstepxDesiredSizeFromSavedName(String savedAccountName) {
		String cleanName = cleanOrDefault(savedAccountName, "").toUpperCase(Locale.US);
		if (cleanName.contains("150")) {
			return 150000.0;
		}
		if (cleanName.contains("100")) {
			return 100000.0;
		}
		if (cleanName.contains("50")) {
			return 50000.0;
		}
		return 0.0;
	}

	public static String getTopstepxPracticeExposure(String requiredAccountId, String symbols) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanOrDefault(requiredAccountId, "");
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\"}";
		}
		if (isBlank(config.accountId) || !accountId.equals(config.accountId.trim())) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before checking exposure.") + "}";
		}

		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			if (!accountId.equals(realtimeConfig.accountId)) {
				return "{\"success\":false,\"message\":" + jsonString("ProjectX authenticated account is not " + accountId + ".") + "}";
			}
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			assertTopstepxAccountCanTrade(realtimeConfig.baseUrl, activeToken, accountId);

			Map<String, String> symbolByContractId = new HashMap<String, String>();
			List<TopstepxContractInfo> contracts = resolveTopstepxRealtimeContracts(realtimeConfig, symbols);
			for (int index = 0; index < contracts.size(); index++) {
				TopstepxContractInfo contract = contracts.get(index);
				if (contract != null && !isBlank(contract.contractId)) {
					symbolByContractId.put(contract.contractId, normalizeFuturesSymbol(contract.symbol));
				}
			}

			HttpResult openPositions = postJson(realtimeConfig.baseUrl + "/Position/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openPositions.statusCode < 200 || openPositions.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-position exposure check failed: " + topstepxErrorSummary(openPositions.body)) + ",\"statusCode\":" + openPositions.statusCode + "}";
			}
			HttpResult openOrders = postJson(realtimeConfig.baseUrl + "/Order/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openOrders.statusCode < 200 || openOrders.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-order exposure check failed: " + topstepxErrorSummary(openOrders.body)) + ",\"statusCode\":" + openOrders.statusCode + "}";
			}

			ExposureTotals totals = new ExposureTotals();
			List<String> positions = extractJsonArrayObjects(openPositions.body, "positions");
			for (int index = 0; index < positions.size(); index++) {
				String position = positions.get(index);
				String symbol = exposureSymbolFromJson(position, symbolByContractId);
				if (isBlank(symbol)) {
					continue;
				}
				addExposure(totals, symbol, exposureSizeFromJson(position), false);
			}

			List<String> orders = extractJsonArrayObjects(openOrders.body, "orders");
			for (int index = 0; index < orders.size(); index++) {
				String order = orders.get(index);
				if (!isLiveEntryOrder(order)) {
					continue;
				}
				String symbol = exposureSymbolFromJson(order, symbolByContractId);
				if (isBlank(symbol)) {
					continue;
				}
				addExposure(totals, symbol, exposureSizeFromJson(order), true);
			}

			return "{"
				+ "\"success\":true,"
				+ "\"message\":\"TopstepX exposure verified.\","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"symbols\":" + jsonString(totals.symbolsCsv()) + ","
				+ "\"positionSlots\":" + totals.activeSymbols.size() + ","
				+ "\"totalContracts\":" + totals.totalContracts + ","
				+ "\"totalMiniUnits\":" + decimal(totals.totalMiniUnits) + ","
				+ "\"brokerPositions\":" + positions.size() + ","
				+ "\"liveEntryOrders\":" + totals.liveEntryOrders
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX exposure check failed: " + safeMessage(e.getMessage())) + "}";
		}
	}

	public static String submitTopstepxPracticeOrder(
		String requiredAccountId,
		String symbol,
		String side,
		int size,
		double entryPrice,
		double stopPrice,
		double targetPrice,
		String customTag
	) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanOrDefault(requiredAccountId, "");
		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		String normalizedSide = normalizeOrderSide(side);
		int safeSize = Math.max(1, Math.min(50, size));
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\"}";
		}
		if (isBlank(config.accountId) || !accountId.equals(config.accountId.trim())) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before placing orders.") + "}";
		}
		if (isBlank(normalizedSide)) {
			return "{\"success\":false,\"message\":\"Order side must be BUY, SELL, LONG, or SHORT.\"}";
		}

		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			if (!accountId.equals(realtimeConfig.accountId)) {
				return "{\"success\":false,\"message\":" + jsonString("ProjectX authenticated account is not " + accountId + ".") + "}";
			}
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			assertTopstepxAccountCanTrade(realtimeConfig.baseUrl, activeToken, accountId);

			List<TopstepxContractInfo> contracts = resolveTopstepxRealtimeContracts(realtimeConfig, normalizedSymbol);
				if (contracts.isEmpty()) {
					return "{\"success\":false,\"message\":" + jsonString("No active ProjectX contract resolved for " + normalizedSymbol + ".") + "}";
				}
				TopstepxContractInfo contract = contracts.get(0);
				String tag = cleanOrDefault(customTag, "live-" + normalizedSymbol + "-" + System.currentTimeMillis());
				TopstepxOrderAttempt attempt = placeTopstepxPracticeOrder(
					realtimeConfig.baseUrl,
					activeToken,
					accountId,
					contract,
					normalizedSide,
					safeSize,
					entryPrice,
					stopPrice,
					targetPrice,
					tag,
					true
				);
				return "{"
					+ "\"success\":" + attempt.success + ","
					+ "\"message\":" + jsonString(topstepxOrderSubmitMessage(attempt)) + ","
					+ "\"statusCode\":" + attempt.statusCode + ","
					+ "\"orderId\":" + (isBlank(attempt.orderId) ? "0" : attempt.orderId) + ","
					+ "\"brokerOrderId\":" + jsonString(isBlank(attempt.orderId) ? "" : attempt.orderId) + ","
					+ "\"accountId\":" + jsonString(accountId) + ","
					+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
					+ "\"contractId\":" + jsonString(contract.contractId) + ","
					+ "\"contractName\":" + jsonString(contract.name) + ","
					+ "\"customTag\":" + jsonString(tag) + ","
					+ "\"bracketsSubmitted\":" + attempt.bracketsSubmitted + ","
					+ "\"bracketFallback\":false,"
					+ "\"fallbackReason\":\"\","
					+ "\"requiresAutoOcoBrackets\":" + (!attempt.success && topstepxBracketModeError(attempt.responseBody)) + ","
					+ "\"request\":" + attempt.requestJson + ","
					+ "\"response\":" + attempt.responseJson
					+ "}";
			} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("ProjectX order submit failed: " + safeMessage(e.getMessage())) + "}";
		}
	}

	public static String closeTopstepxPracticeSymbolPosition(String requiredAccountId, String symbol) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanOrDefault(requiredAccountId, "");
		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\"}";
		}
		if (isBlank(config.accountId) || !accountId.equals(config.accountId.trim())) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before closing positions.") + "}";
		}
		if (isBlank(normalizedSymbol)) {
			return "{\"success\":false,\"message\":\"Choose a futures symbol to close.\"}";
		}

		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			if (!accountId.equals(realtimeConfig.accountId)) {
				return "{\"success\":false,\"message\":" + jsonString("ProjectX authenticated account is not " + accountId + ".") + "}";
			}
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			assertTopstepxAccountCanTrade(realtimeConfig.baseUrl, activeToken, accountId);

			Map<String, String> symbolByContractId = new HashMap<String, String>();
			Set<String> contractIds = new HashSet<String>();
			List<TopstepxContractInfo> contracts = resolveTopstepxRealtimeContracts(realtimeConfig, normalizedSymbol);
			for (int index = 0; index < contracts.size(); index++) {
				TopstepxContractInfo contract = contracts.get(index);
				if (contract != null && !isBlank(contract.contractId)) {
					contractIds.add(contract.contractId);
					symbolByContractId.put(contract.contractId, normalizeFuturesSymbol(contract.symbol));
				}
			}

			HttpResult openPositions = postJson(realtimeConfig.baseUrl + "/Position/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openPositions.statusCode < 200 || openPositions.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-position search failed: " + topstepxErrorSummary(openPositions.body)) + ",\"statusCode\":" + openPositions.statusCode + ",\"response\":" + syncResponseJson(openPositions, "positions") + "}";
			}
			List<String> positions = extractJsonArrayObjects(openPositions.body, "positions");
			StringBuilder positionResults = new StringBuilder("[");
			int matchingPositions = 0;
			int closeSuccesses = 0;
			int closeFailures = 0;
			for (int index = 0; index < positions.size(); index++) {
				String position = positions.get(index);
				String contractId = firstNonBlank(
					extractJsonString(position, "contractId"),
					extractJsonString(position, "contractID"),
					extractJsonString(position, "contract")
				);
				String positionSymbol = exposureSymbolFromJson(position, symbolByContractId);
				if (!normalizedSymbol.equals(positionSymbol) && !contractIds.contains(contractId)) {
					continue;
				}
				if (matchingPositions > 0) {
					positionResults.append(",");
				}
				matchingPositions++;
				if (isBlank(contractId)) {
					closeFailures++;
					positionResults.append("{\"success\":false,\"message\":\"Position did not include a contractId.\",\"position\":").append(position).append("}");
					continue;
				}
				String body = "{\"accountId\":" + accountId + ",\"contractId\":" + jsonString(contractId) + "}";
				HttpResult close = postJson(realtimeConfig.baseUrl + "/Position/closeContract", body, activeToken);
				boolean ok = close.statusCode >= 200 && close.statusCode < 300 && jsonBoolean(close.body, "success");
				if (ok) {
					closeSuccesses++;
				} else {
					closeFailures++;
				}
				positionResults.append("{")
					.append("\"success\":").append(ok).append(",")
					.append("\"contractId\":").append(jsonString(contractId)).append(",")
					.append("\"statusCode\":").append(close.statusCode).append(",")
					.append("\"response\":").append(syncResponseJson(close, "close"))
					.append("}");
			}
			positionResults.append("]");

			HttpResult openOrders = postJson(realtimeConfig.baseUrl + "/Order/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openOrders.statusCode < 200 || openOrders.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-order search failed: " + topstepxErrorSummary(openOrders.body)) + ",\"statusCode\":" + openOrders.statusCode + ",\"positionResults\":" + positionResults + ",\"response\":" + syncResponseJson(openOrders, "orders") + "}";
			}
			List<String> orders = extractJsonArrayObjects(openOrders.body, "orders");
			StringBuilder cancelResults = new StringBuilder("[");
			int matchingOrders = 0;
			int cancelSuccesses = 0;
			int cancelFailures = 0;
			boolean canCancelOrders = closeFailures == 0;
			for (int index = 0; index < orders.size(); index++) {
				String order = orders.get(index);
				String contractId = firstNonBlank(
					extractJsonString(order, "contractId"),
					extractJsonString(order, "contractID"),
					extractJsonString(order, "contract")
				);
				String orderSymbol = exposureSymbolFromJson(order, symbolByContractId);
				if (!normalizedSymbol.equals(orderSymbol) && !contractIds.contains(contractId)) {
					continue;
				}
				String orderId = firstNonBlank(
					extractJsonNumber(order, "id"),
					extractJsonNumber(order, "orderId"),
					extractJsonString(order, "id"),
					extractJsonString(order, "orderId")
				);
				if (matchingOrders > 0) {
					cancelResults.append(",");
				}
				matchingOrders++;
				if (!canCancelOrders) {
					cancelResults.append("{\"success\":false,\"message\":\"Skipped cancel so protective orders remain after a close failure.\",\"order\":").append(order).append("}");
					continue;
				}
				if (isBlank(orderId)) {
					cancelFailures++;
					cancelResults.append("{\"success\":false,\"message\":\"Open order did not include an id.\",\"order\":").append(order).append("}");
					continue;
				}
				String body = "{\"accountId\":" + accountId + ",\"orderId\":" + orderId + "}";
				HttpResult cancel = postJson(realtimeConfig.baseUrl + "/Order/cancel", body, activeToken);
				boolean ok = cancel.statusCode >= 200 && cancel.statusCode < 300 && jsonBoolean(cancel.body, "success");
				if (ok) {
					cancelSuccesses++;
				} else {
					cancelFailures++;
				}
				cancelResults.append("{")
					.append("\"success\":").append(ok).append(",")
					.append("\"orderId\":").append(jsonString(orderId)).append(",")
					.append("\"statusCode\":").append(cancel.statusCode).append(",")
					.append("\"response\":").append(syncResponseJson(cancel, "cancel"))
					.append("}");
			}
			cancelResults.append("]");

			boolean success = matchingPositions > 0 && closeFailures == 0 && cancelFailures == 0;
			return "{"
				+ "\"success\":" + success + ","
				+ "\"message\":" + jsonString(success ? "TopstepX position closed for " + normalizedSymbol + "." : (matchingPositions <= 0 ? "No open TopstepX position found for " + normalizedSymbol + "." : "TopstepX close for " + normalizedSymbol + " needs attention.")) + ","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
				+ "\"positionsFound\":" + positions.size() + ","
				+ "\"matchingPositions\":" + matchingPositions + ","
				+ "\"positionsClosed\":" + closeSuccesses + ","
				+ "\"positionCloseFailures\":" + closeFailures + ","
				+ "\"ordersFound\":" + orders.size() + ","
				+ "\"matchingOrders\":" + matchingOrders + ","
				+ "\"ordersCanceled\":" + cancelSuccesses + ","
				+ "\"orderCancelFailures\":" + cancelFailures + ","
				+ "\"positionResults\":" + positionResults + ","
				+ "\"orderResults\":" + cancelResults
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX symbol close failed: " + safeMessage(e.getMessage())) + "}";
		}
	}

	public static String partialCloseTopstepxPracticeSymbolPosition(String requiredAccountId, String symbol, int size) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanOrDefault(requiredAccountId, "");
		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		int safeSize = Math.max(1, Math.min(50, size));
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\"}";
		}
		if (isBlank(config.accountId) || !accountId.equals(config.accountId.trim())) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before partially closing positions.") + "}";
		}
		if (isBlank(normalizedSymbol)) {
			return "{\"success\":false,\"message\":\"Choose a futures symbol to partially close.\"}";
		}
		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			assertTopstepxAccountCanTrade(realtimeConfig.baseUrl, activeToken, accountId);
			Map<String, String> symbolByContractId = new HashMap<String, String>();
			Set<String> contractIds = new HashSet<String>();
			List<TopstepxContractInfo> contracts = resolveTopstepxRealtimeContracts(realtimeConfig, normalizedSymbol);
			for (int index = 0; index < contracts.size(); index++) {
				TopstepxContractInfo contract = contracts.get(index);
				if (contract != null && !isBlank(contract.contractId)) {
					contractIds.add(contract.contractId);
					symbolByContractId.put(contract.contractId, normalizeFuturesSymbol(contract.symbol));
				}
			}
			HttpResult openPositions = postJson(realtimeConfig.baseUrl + "/Position/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openPositions.statusCode < 200 || openPositions.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-position search failed: " + topstepxErrorSummary(openPositions.body)) + ",\"statusCode\":" + openPositions.statusCode + ",\"response\":" + syncResponseJson(openPositions, "positions") + "}";
			}
			List<String> positions = extractJsonArrayObjects(openPositions.body, "positions");
			StringBuilder results = new StringBuilder("[");
			int matchingPositions = 0;
			int closeSuccesses = 0;
			int closeFailures = 0;
			for (int index = 0; index < positions.size(); index++) {
				String position = positions.get(index);
				String contractId = firstNonBlank(extractJsonString(position, "contractId"), extractJsonString(position, "contractID"), extractJsonString(position, "contract"));
				String positionSymbol = exposureSymbolFromJson(position, symbolByContractId);
				if (!normalizedSymbol.equals(positionSymbol) && !contractIds.contains(contractId)) {
					continue;
				}
				int openSize = Math.max(0, exposureSizeFromJson(position));
				int closeSize = Math.min(safeSize, Math.max(0, openSize - 1));
				if (matchingPositions > 0) results.append(",");
				matchingPositions++;
				if (isBlank(contractId) || closeSize <= 0) {
					closeFailures++;
					results.append("{\"success\":false,\"message\":\"Position cannot be partially closed without leaving a runner.\",\"position\":").append(position).append("}");
					continue;
				}
				String body = "{\"accountId\":" + accountId + ",\"contractId\":" + jsonString(contractId) + ",\"size\":" + closeSize + "}";
				HttpResult close = postJson(realtimeConfig.baseUrl + "/Position/partialCloseContract", body, activeToken);
				boolean ok = close.statusCode >= 200 && close.statusCode < 300 && jsonBoolean(close.body, "success");
				if (ok) closeSuccesses++; else closeFailures++;
				results.append("{")
					.append("\"success\":").append(ok).append(",")
					.append("\"contractId\":").append(jsonString(contractId)).append(",")
					.append("\"requestedSize\":").append(closeSize).append(",")
					.append("\"statusCode\":").append(close.statusCode).append(",")
					.append("\"response\":").append(syncResponseJson(close, "partialClose"))
					.append("}");
				break;
			}
			results.append("]");
			boolean success = matchingPositions > 0 && closeSuccesses > 0 && closeFailures == 0;
			return "{"
				+ "\"success\":" + success + ","
				+ "\"message\":" + jsonString(success ? "TopstepX partial close submitted for " + normalizedSymbol + "." : (matchingPositions <= 0 ? "No open TopstepX position found for " + normalizedSymbol + "." : "TopstepX partial close needs attention.")) + ","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
				+ "\"requestedSize\":" + safeSize + ","
				+ "\"matchingPositions\":" + matchingPositions + ","
				+ "\"partialsClosed\":" + closeSuccesses + ","
				+ "\"partialCloseFailures\":" + closeFailures + ","
				+ "\"positionResults\":" + results
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX partial close failed: " + safeMessage(e.getMessage())) + "}";
		}
	}

	public static String modifyTopstepxPracticeProtectiveOrders(String requiredAccountId, String symbol, double stopPrice, double targetPrice, int remainingSize) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanOrDefault(requiredAccountId, "");
		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		int safeSize = remainingSize <= 0 ? 0 : Math.max(1, Math.min(50, remainingSize));
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\"}";
		}
		if (isBlank(config.accountId) || !accountId.equals(config.accountId.trim())) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before modifying protective orders.") + "}";
		}
		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			assertTopstepxAccountCanTrade(realtimeConfig.baseUrl, activeToken, accountId);
			Map<String, String> symbolByContractId = new HashMap<String, String>();
			Set<String> contractIds = new HashSet<String>();
			List<TopstepxContractInfo> contracts = resolveTopstepxRealtimeContracts(realtimeConfig, normalizedSymbol);
			for (int index = 0; index < contracts.size(); index++) {
				TopstepxContractInfo contract = contracts.get(index);
				if (contract != null && !isBlank(contract.contractId)) {
					contractIds.add(contract.contractId);
					symbolByContractId.put(contract.contractId, normalizeFuturesSymbol(contract.symbol));
				}
			}
			HttpResult openOrders = postJson(realtimeConfig.baseUrl + "/Order/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openOrders.statusCode < 200 || openOrders.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-order search failed: " + topstepxErrorSummary(openOrders.body)) + ",\"statusCode\":" + openOrders.statusCode + ",\"response\":" + syncResponseJson(openOrders, "orders") + "}";
			}
			List<String> orders = extractJsonArrayObjects(openOrders.body, "orders");
			StringBuilder results = new StringBuilder("[");
			int matched = 0;
			int modified = 0;
			int failures = 0;
			for (int index = 0; index < orders.size(); index++) {
				String order = orders.get(index);
				String contractId = firstNonBlank(extractJsonString(order, "contractId"), extractJsonString(order, "contractID"), extractJsonString(order, "contract"));
				String orderSymbol = exposureSymbolFromJson(order, symbolByContractId);
				if (!normalizedSymbol.equals(orderSymbol) && !contractIds.contains(contractId)) {
					continue;
				}
				double existingStop = firstJsonNumber(order, "stopPrice");
				double existingLimit = firstJsonNumber(order, "limitPrice", "price");
				int type = (int) Math.round(firstJsonNumber(order, "type", "orderType"));
				boolean stopOrder = existingStop > 0.0 || type == 3 || type == 4 || type == 5;
				boolean targetOrder = !stopOrder && existingLimit > 0.0;
				if ((stopOrder && stopPrice <= 0.0) || (targetOrder && targetPrice <= 0.0) || (!stopOrder && !targetOrder)) {
					continue;
				}
				String orderId = firstNonBlank(extractJsonNumber(order, "id"), extractJsonNumber(order, "orderId"), extractJsonString(order, "id"), extractJsonString(order, "orderId"));
				if (isBlank(orderId)) {
					failures++;
					continue;
				}
				if (matched > 0) results.append(",");
				matched++;
				String body = "{\"accountId\":" + accountId
					+ ",\"orderId\":" + orderId
					+ ",\"size\":" + (safeSize > 0 ? String.valueOf(safeSize) : "null")
					+ ",\"limitPrice\":" + (targetOrder ? decimal(targetPrice) : "null")
					+ ",\"stopPrice\":" + (stopOrder ? decimal(stopPrice) : "null")
					+ ",\"trailPrice\":null}";
				HttpResult modify = postJson(realtimeConfig.baseUrl + "/Order/modify", body, activeToken);
				boolean ok = modify.statusCode >= 200 && modify.statusCode < 300 && jsonBoolean(modify.body, "success");
				if (ok) modified++; else failures++;
				results.append("{")
					.append("\"success\":").append(ok).append(",")
					.append("\"orderId\":").append(jsonString(orderId)).append(",")
					.append("\"orderKind\":").append(jsonString(stopOrder ? "STOP" : "TARGET")).append(",")
					.append("\"statusCode\":").append(modify.statusCode).append(",")
					.append("\"request\":").append(body).append(",")
					.append("\"response\":").append(syncResponseJson(modify, "modify"))
					.append("}");
			}
			results.append("]");
			boolean success = matched > 0 && modified > 0 && failures == 0;
			return "{"
				+ "\"success\":" + success + ","
				+ "\"message\":" + jsonString(success ? "TopstepX protective order modification submitted for " + normalizedSymbol + "." : "No matching protective order was safely modified for " + normalizedSymbol + ".") + ","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
				+ "\"matchedOrders\":" + matched + ","
				+ "\"modifiedOrders\":" + modified + ","
				+ "\"modifyFailures\":" + failures + ","
				+ "\"orderResults\":" + results
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX protective order modification failed: " + safeMessage(e.getMessage())) + "}";
		}
	}

	public static String cancelTopstepxPracticeRestingEntryOrders(String requiredAccountId) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanOrDefault(requiredAccountId, "");
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopStep API connection is disabled.\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopStep API username/API key are missing.\"}";
		}
		if (isBlank(config.accountId) || !accountId.equals(config.accountId.trim())) {
			return "{\"success\":false,\"message\":" + jsonString("TopStep API configured account must be " + accountId + " before canceling orders.") + "}";
		}

		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			if (!accountId.equals(realtimeConfig.accountId)) {
				return "{\"success\":false,\"message\":" + jsonString("ProjectX authenticated account is not " + accountId + ".") + "}";
			}
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			assertTopstepxAccountCanTrade(realtimeConfig.baseUrl, activeToken, accountId);

			HttpResult openPositions = postJson(realtimeConfig.baseUrl + "/Position/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openPositions.statusCode < 200 || openPositions.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopStep API open-position search failed: " + topstepxErrorSummary(openPositions.body)) + ",\"statusCode\":" + openPositions.statusCode + ",\"response\":" + syncResponseJson(openPositions, "positions") + "}";
			}
			List<String> positions = extractJsonArrayObjects(openPositions.body, "positions");
			if (!positions.isEmpty()) {
				return "{"
					+ "\"success\":true,"
					+ "\"skipped\":true,"
					+ "\"message\":\"Open positions exist, so the cancel-only sweep skipped open orders to avoid removing protective brackets before the 4:00 PM flatten sweep.\","
					+ "\"accountId\":" + jsonString(accountId) + ","
					+ "\"positionsFound\":" + positions.size() + ","
					+ "\"ordersCanceled\":0,"
					+ "\"positionResults\":" + syncResponseJson(openPositions, "positions")
					+ "}";
			}

			HttpResult openOrders = postJson(realtimeConfig.baseUrl + "/Order/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openOrders.statusCode < 200 || openOrders.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopStep API open-order search failed: " + topstepxErrorSummary(openOrders.body)) + ",\"statusCode\":" + openOrders.statusCode + ",\"response\":" + syncResponseJson(openOrders, "orders") + "}";
			}
			List<String> orders = extractJsonArrayObjects(openOrders.body, "orders");
			StringBuilder cancelResults = new StringBuilder("[");
			int cancelSuccesses = 0;
			int cancelFailures = 0;
			for (int index = 0; index < orders.size(); index++) {
				String order = orders.get(index);
				String orderId = firstNonBlank(
					extractJsonNumber(order, "id"),
					extractJsonNumber(order, "orderId"),
					extractJsonString(order, "id"),
					extractJsonString(order, "orderId")
				);
				if (index > 0) {
					cancelResults.append(",");
				}
				if (isBlank(orderId)) {
					cancelFailures++;
					cancelResults.append("{\"success\":false,\"message\":\"Open order did not include an id.\",\"order\":").append(order).append("}");
					continue;
				}
				String body = "{\"accountId\":" + accountId + ",\"orderId\":" + orderId + "}";
				HttpResult cancel = postJson(realtimeConfig.baseUrl + "/Order/cancel", body, activeToken);
				boolean ok = cancel.statusCode >= 200 && cancel.statusCode < 300 && jsonBoolean(cancel.body, "success");
				if (ok) {
					cancelSuccesses++;
				} else {
					cancelFailures++;
				}
				cancelResults.append("{")
					.append("\"success\":").append(ok).append(",")
					.append("\"orderId\":").append(jsonString(orderId)).append(",")
					.append("\"statusCode\":").append(cancel.statusCode).append(",")
					.append("\"response\":").append(syncResponseJson(cancel, "cancel"))
					.append("}");
			}
			cancelResults.append("]");

			boolean success = cancelFailures == 0;
			return "{"
				+ "\"success\":" + success + ","
				+ "\"message\":" + jsonString(success ? "TopStep API resting-order cancel sweep completed." : "TopStep API resting-order cancel sweep needs attention.") + ","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"positionsFound\":0,"
				+ "\"ordersFound\":" + orders.size() + ","
				+ "\"ordersCanceled\":" + cancelSuccesses + ","
				+ "\"orderCancelFailures\":" + cancelFailures + ","
				+ "\"orderResults\":" + cancelResults
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopStep API resting-order cancel failed: " + safeMessage(e.getMessage())) + "}";
		}
	}

	public static String flattenTopstepxPracticeAccount(String requiredAccountId) {
		initializeStore();
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanOrDefault(requiredAccountId, "");
		if (!config.enabled) {
			return "{\"success\":false,\"message\":\"TopstepX connection is disabled.\"}";
		}
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "{\"success\":false,\"message\":\"TopstepX username/API key are missing.\"}";
		}
		if (isBlank(config.accountId) || !accountId.equals(config.accountId.trim())) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before flattening positions.") + "}";
		}

		try {
			TopstepxRealtimeConfig realtimeConfig = createTopstepxRealtimeConfig();
			if (!accountId.equals(realtimeConfig.accountId)) {
				return "{\"success\":false,\"message\":" + jsonString("ProjectX authenticated account is not " + accountId + ".") + "}";
			}
			String token = realtimeConfig.token;
			HttpResult validate = postJson(realtimeConfig.baseUrl + "/Auth/validate", "{}", token);
			String refreshedToken = extractJsonString(validate.body, "newToken");
			String activeToken = isBlank(refreshedToken) ? token : refreshedToken;
			assertTopstepxAccountCanTrade(realtimeConfig.baseUrl, activeToken, accountId);

			HttpResult openPositions = postJson(realtimeConfig.baseUrl + "/Position/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openPositions.statusCode < 200 || openPositions.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-position search failed: " + topstepxErrorSummary(openPositions.body)) + ",\"statusCode\":" + openPositions.statusCode + ",\"response\":" + syncResponseJson(openPositions, "positions") + "}";
			}
			List<String> positions = extractJsonArrayObjects(openPositions.body, "positions");
			StringBuilder positionResults = new StringBuilder("[");
			int closeSuccesses = 0;
			int closeFailures = 0;
			for (int index = 0; index < positions.size(); index++) {
				String position = positions.get(index);
				String contractId = firstNonBlank(
					extractJsonString(position, "contractId"),
					extractJsonString(position, "contractID"),
					extractJsonString(position, "contract")
				);
				if (index > 0) {
					positionResults.append(",");
				}
				if (isBlank(contractId)) {
					closeFailures++;
					positionResults.append("{\"success\":false,\"message\":\"Position did not include a contractId.\",\"position\":").append(position).append("}");
					continue;
				}
				String body = "{\"accountId\":" + accountId + ",\"contractId\":" + jsonString(contractId) + "}";
				HttpResult close = postJson(realtimeConfig.baseUrl + "/Position/closeContract", body, activeToken);
				boolean ok = close.statusCode >= 200 && close.statusCode < 300 && jsonBoolean(close.body, "success");
				if (ok) {
					closeSuccesses++;
				} else {
					closeFailures++;
				}
				positionResults.append("{")
					.append("\"success\":").append(ok).append(",")
					.append("\"contractId\":").append(jsonString(contractId)).append(",")
					.append("\"statusCode\":").append(close.statusCode).append(",")
					.append("\"response\":").append(syncResponseJson(close, "close"))
					.append("}");
			}
			positionResults.append("]");

			HttpResult openOrders = postJson(realtimeConfig.baseUrl + "/Order/searchOpen", "{\"accountId\":" + accountId + "}", activeToken);
			if (openOrders.statusCode < 200 || openOrders.statusCode >= 300) {
				return "{\"success\":false,\"message\":" + jsonString("TopstepX open-order search failed: " + topstepxErrorSummary(openOrders.body)) + ",\"statusCode\":" + openOrders.statusCode + ",\"positionResults\":" + positionResults + ",\"response\":" + syncResponseJson(openOrders, "orders") + "}";
			}
			List<String> orders = extractJsonArrayObjects(openOrders.body, "orders");
			StringBuilder cancelResults = new StringBuilder("[");
			int cancelSuccesses = 0;
			int cancelFailures = 0;
			boolean canCancelOrders = closeFailures == 0;
			for (int index = 0; index < orders.size(); index++) {
				String order = orders.get(index);
				String orderId = firstNonBlank(
					extractJsonNumber(order, "id"),
					extractJsonNumber(order, "orderId"),
					extractJsonString(order, "id"),
					extractJsonString(order, "orderId")
				);
				if (index > 0) {
					cancelResults.append(",");
				}
				if (!canCancelOrders) {
					cancelResults.append("{\"success\":false,\"message\":\"Skipped cancel so protective orders remain after a close failure.\",\"order\":").append(order).append("}");
					continue;
				}
				if (isBlank(orderId)) {
					cancelFailures++;
					cancelResults.append("{\"success\":false,\"message\":\"Open order did not include an id.\",\"order\":").append(order).append("}");
					continue;
				}
				String body = "{\"accountId\":" + accountId + ",\"orderId\":" + orderId + "}";
				HttpResult cancel = postJson(realtimeConfig.baseUrl + "/Order/cancel", body, activeToken);
				boolean ok = cancel.statusCode >= 200 && cancel.statusCode < 300 && jsonBoolean(cancel.body, "success");
				if (ok) {
					cancelSuccesses++;
				} else {
					cancelFailures++;
				}
				cancelResults.append("{")
					.append("\"success\":").append(ok).append(",")
					.append("\"orderId\":").append(jsonString(orderId)).append(",")
					.append("\"statusCode\":").append(cancel.statusCode).append(",")
					.append("\"response\":").append(syncResponseJson(cancel, "cancel"))
					.append("}");
			}
			cancelResults.append("]");

			boolean success = closeFailures == 0 && cancelFailures == 0;
			return "{"
				+ "\"success\":" + success + ","
				+ "\"message\":" + jsonString(success ? "TopstepX flatten/cancel sweep completed." : "TopstepX flatten/cancel sweep needs attention.") + ","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"positionsFound\":" + positions.size() + ","
				+ "\"positionsClosed\":" + closeSuccesses + ","
				+ "\"positionCloseFailures\":" + closeFailures + ","
				+ "\"ordersFound\":" + orders.size() + ","
				+ "\"ordersCanceled\":" + cancelSuccesses + ","
				+ "\"orderCancelFailures\":" + cancelFailures + ","
				+ "\"positionResults\":" + positionResults + ","
				+ "\"orderResults\":" + cancelResults
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("TopstepX flatten/cancel failed: " + safeMessage(e.getMessage())) + "}";
		}
	}

	private static void assertTopstepxAccountCanTrade(String baseUrl, String token, String accountId) throws Exception {
		HttpResult accounts = postJson(baseUrl + "/Account/search", "{\"onlyActiveAccounts\":true}", token);
		if (accounts.statusCode < 200 || accounts.statusCode >= 300) {
			throw new IllegalStateException("account search failed (" + accounts.statusCode + "): " + summarizeBody(accounts.body));
		}
		List<String> accountObjects = extractJsonArrayObjects(accounts.body, "accounts");
		for (int index = 0; index < accountObjects.size(); index++) {
			String object = accountObjects.get(index);
			String id = cleanOrDefault(extractJsonNumber(object, "id"), extractJsonString(object, "id"));
			if (accountId.equals(id)) {
				if (!jsonBoolean(object, "canTrade")) {
					throw new IllegalStateException("TopstepX account " + accountId + " is active but canTrade is false.");
				}
				return;
			}
		}
		throw new IllegalStateException("TopstepX account " + accountId + " was not returned by active account search.");
	}

	private static class ExposureTotals {
		private Set<String> activeSymbols = new HashSet<String>();
		private int totalContracts;
		private double totalMiniUnits;
		private int liveEntryOrders;

		private String symbolsCsv() {
			List<String> symbols = new ArrayList<String>(activeSymbols);
			Collections.sort(symbols);
			StringBuilder csv = new StringBuilder();
			for (int index = 0; index < symbols.size(); index++) {
				if (index > 0) {
					csv.append(",");
				}
				csv.append(symbols.get(index));
			}
			return csv.toString();
		}
	}

	private static void addExposure(ExposureTotals totals, String symbol, int contracts, boolean liveEntryOrder) {
		if (totals == null || isBlank(symbol) || contracts <= 0) {
			return;
		}
		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		totals.activeSymbols.add(normalizedSymbol);
		totals.totalContracts += contracts;
		totals.totalMiniUnits += fundedMiniUnitsForSymbol(normalizedSymbol) * contracts;
		if (liveEntryOrder) {
			totals.liveEntryOrders++;
		}
	}

	private static String exposureSymbolFromJson(String objectJson, Map<String, String> symbolByContractId) {
		String contractId = firstNonBlank(
			extractJsonString(objectJson, "contractId"),
			extractJsonString(objectJson, "contractID"),
			extractJsonString(objectJson, "contract")
		);
		if (!isBlank(contractId) && symbolByContractId != null && symbolByContractId.containsKey(contractId)) {
			return symbolByContractId.get(contractId);
		}
		String directSymbol = firstNonBlank(
			extractJsonString(objectJson, "symbol"),
			extractJsonString(objectJson, "symbolId"),
			extractJsonString(objectJson, "contractSymbol")
		);
		String inferred = inferFuturesSymbol(directSymbol);
		if (!isBlank(inferred)) {
			return inferred;
		}
		return inferFuturesSymbol(contractId + " "
			+ extractJsonString(objectJson, "contractName") + " "
			+ extractJsonString(objectJson, "name") + " "
			+ extractJsonString(objectJson, "description"));
	}

	private static String inferFuturesSymbol(String value) {
		if (isBlank(value)) {
			return "";
		}
		String normalized = value.toUpperCase(Locale.US);
		String[] symbols = {"MNQ", "MES", "M2K", "MYM", "MGC", "MCL", "NQ", "ES", "GC"};
		for (int index = 0; index < symbols.length; index++) {
			if (normalized.contains(symbols[index])) {
				return symbols[index];
			}
		}
		return "";
	}

	private static int exposureSizeFromJson(String objectJson) {
		String sizeValue = firstNonBlank(
			extractJsonNumber(objectJson, "size"),
			extractJsonNumber(objectJson, "qty"),
			extractJsonNumber(objectJson, "quantity"),
			extractJsonNumber(objectJson, "netPos"),
			extractJsonNumber(objectJson, "netQuantity"),
			extractJsonNumber(objectJson, "positionSize"),
			extractJsonNumber(objectJson, "openSize"),
			extractJsonNumber(objectJson, "remainingSize")
		);
		int size = (int) Math.round(Math.abs(parseDouble(sizeValue)));
		return Math.max(1, size);
	}

	private static boolean isLiveEntryOrder(String objectJson) {
		String tag = firstNonBlank(
			extractJsonString(objectJson, "customTag"),
			extractJsonString(objectJson, "tag"),
			extractJsonString(objectJson, "text")
		);
		return !isBlank(tag) && tag.toLowerCase(Locale.US).startsWith("live-");
	}

	private static double fundedMiniUnitsForSymbol(String symbol) {
		String normalized = normalizeFuturesSymbol(symbol);
		return "MES".equals(normalized) || "MNQ".equals(normalized) || "M2K".equals(normalized) || "MYM".equals(normalized)
			|| "MGC".equals(normalized) || "MCL".equals(normalized) ? 0.1 : 1.0;
	}

	private static String normalizeOrderSide(String side) {
		String normalized = cleanOrDefault(side, "").toUpperCase(Locale.US);
		if ("LONG".equals(normalized) || "BUY".equals(normalized) || "BID".equals(normalized)) {
			return "BUY";
		}
		if ("SHORT".equals(normalized) || "SELL".equals(normalized) || "ASK".equals(normalized)) {
			return "SELL";
		}
		return "";
	}

	private static void appendBracketObjects(StringBuilder body, TopstepxContractInfo contract, double entryPrice, double stopPrice, double targetPrice) {
		if (entryPrice <= 0.0 || contract == null || contract.tickSize <= 0.0) {
			return;
		}
		int stopTicks = bracketTicksFromEntry(entryPrice, stopPrice, contract.tickSize);
		int targetTicks = bracketTicksFromEntry(entryPrice, targetPrice, contract.tickSize);
		if (stopTicks != 0) {
			body.append(",\"stopLossBracket\":{\"ticks\":").append(stopTicks).append(",\"type\":4}");
		}
		if (targetTicks != 0) {
			body.append(",\"takeProfitBracket\":{\"ticks\":").append(targetTicks).append(",\"type\":1}");
		}
	}

	static int bracketTicksFromEntry(double entryPrice, double bracketPrice, double tickSize) {
		if (entryPrice <= 0.0 || bracketPrice <= 0.0 || tickSize <= 0.0) {
			return 0;
		}
		double rawTicks = (bracketPrice - entryPrice) / tickSize;
		int ticks = (int) Math.round(rawTicks);
		if (ticks == 0 && Math.abs(rawTicks) > 0.000001) {
			return rawTicks > 0.0 ? 1 : -1;
		}
		return ticks;
	}

	private static TopstepxOrderAttempt placeTopstepxPracticeOrder(
		String baseUrl,
		String token,
		String accountId,
		TopstepxContractInfo contract,
		String normalizedSide,
		int safeSize,
		double entryPrice,
		double stopPrice,
		double targetPrice,
		String tag,
		boolean allowBrackets
	) throws Exception {
		TopstepxOrderAttempt attempt = new TopstepxOrderAttempt();
		int orderType = entryPrice > 0.0 ? 1 : 2;
		int sideCode = "BUY".equals(normalizedSide) ? 0 : 1;
		StringBuilder body = new StringBuilder("{")
			.append("\"accountId\":").append(accountId).append(",")
			.append("\"contractId\":").append(jsonString(contract.contractId)).append(",")
			.append("\"type\":").append(orderType).append(",")
			.append("\"side\":").append(sideCode).append(",")
			.append("\"size\":").append(safeSize).append(",")
			.append("\"limitPrice\":").append(entryPrice > 0.0 ? decimal(entryPrice) : "null").append(",")
			.append("\"stopPrice\":null,")
			.append("\"trailPrice\":null,")
			.append("\"customTag\":").append(jsonString(tag));
		if (allowBrackets) {
			appendBracketObjects(body, contract, entryPrice, stopPrice, targetPrice);
		}
		body.append("}");
		HttpResult order = postJson(baseUrl + "/Order/place", body.toString(), token);
		attempt.statusCode = order.statusCode;
		attempt.responseBody = cleanOrDefault(order.body, "");
		attempt.responseJson = syncResponseJson(order, "order");
		attempt.requestJson = body.toString();
		attempt.success = order.statusCode >= 200 && order.statusCode < 300 && jsonBoolean(order.body, "success");
		attempt.orderId = extractJsonNumber(order.body, "orderId");
		attempt.bracketsSubmitted = allowBrackets && body.indexOf("Bracket") >= 0;
		return attempt;
	}

	private static boolean topstepxBracketModeError(String body) {
		String clean = cleanOrDefault(body, "").toLowerCase(Locale.US);
		return clean.contains("brackets cannot be used with position brackets")
			|| clean.contains("auto oco brackets");
	}

	private static String topstepxOrderSubmitMessage(TopstepxOrderAttempt attempt) {
		if (attempt != null && attempt.success) {
			return "ProjectX order submitted with requested bracket attachment settings.";
		}
		String responseBody = attempt == null ? "" : attempt.responseBody;
		if (topstepxBracketModeError(responseBody)) {
			return "ProjectX rejected bracket attachments because this TopstepX account is still using Position Brackets. Enable Auto OCO Brackets in TopstepX Risk Settings before live strategy orders can submit.";
		}
		return "ProjectX order submit failed: " + topstepxErrorSummary(responseBody);
	}

	private static String findAccountObject(List<String> accountObjects, String accountId) {
		if (accountObjects == null || accountObjects.isEmpty() || isBlank(accountId)) {
			return "";
		}
		for (int index = 0; index < accountObjects.size(); index++) {
			String object = accountObjects.get(index);
			String id = cleanOrDefault(extractJsonNumber(object, "id"), extractJsonString(object, "id"));
			if (accountId.equals(id)) {
				return object;
			}
		}
		return "";
	}

	private static double firstJsonNumber(String json, String... fields) {
		if (json == null || fields == null) {
			return Double.NaN;
		}
		for (int index = 0; index < fields.length; index++) {
			String value = extractJsonNumber(json, fields[index]);
			if (isBlank(value)) {
				continue;
			}
			try {
				return Double.parseDouble(value.replace("\"", "").trim());
			} catch (Exception ignored) {
			}
		}
		return Double.NaN;
	}

	static double topstepxTradeCost(String tradeJson) {
		double fees = firstJsonNumber(tradeJson, "fees", "fee");
		double commission = firstJsonNumber(tradeJson, "commission", "commissions");
		double total = 0.0;
		boolean hasCost = false;
		if (!Double.isNaN(fees)) {
			total += fees;
			hasCost = true;
		}
		if (!Double.isNaN(commission)) {
			total += commission;
			hasCost = true;
		}
		return hasCost ? total : Double.NaN;
	}

	static double topstepxTotalTradeCosts(List<String> tradeJsonObjects) {
		double total = 0.0;
		for (String tradeJson : tradeJsonObjects) {
			double cost = topstepxTradeCost(tradeJson);
			if (!Double.isNaN(cost)) {
				total += cost;
			}
		}
		return total;
	}

	private static String numberOrZero(double value) {
		return Double.isNaN(value) || Double.isInfinite(value) ? "0" : decimal(value);
	}

	private static String jsonNumberOrString(String json, String field) {
		String number = extractJsonNumber(json, field);
		if (!isBlank(number)) {
			return number;
		}
		return jsonString(extractJsonString(json, field));
	}

	private static String topstepxPositionSide(String positionJson) {
		String direct = firstNonBlank(
			extractJsonString(positionJson, "side"),
			extractJsonString(positionJson, "positionSide"),
			extractJsonString(positionJson, "positionType")
		).toUpperCase(Locale.US);
		if (direct.contains("SHORT") || "SELL".equals(direct) || "ASK".equals(direct)) {
			return "SHORT";
		}
		if (direct.contains("LONG") || "BUY".equals(direct) || "BID".equals(direct)) {
			return "LONG";
		}
		int type = (int) Math.round(firstJsonNumber(positionJson, "type"));
		if (type == 2) {
			return "SHORT";
		}
		if (type == 1) {
			return "LONG";
		}
		double signedSize = firstJsonNumber(positionJson, "size", "netPos", "netQuantity", "positionSize");
		return signedSize < 0.0 ? "SHORT" : "LONG";
	}

	private static String topstepxTradeSide(String tradeJson) {
		String direct = firstNonBlank(
			extractJsonString(tradeJson, "side"),
			extractJsonString(tradeJson, "orderSide")
		).toUpperCase(Locale.US);
		if (direct.contains("SELL") || direct.contains("SHORT") || "ASK".equals(direct)) {
			return "SELL";
		}
		if (direct.contains("BUY") || direct.contains("LONG") || "BID".equals(direct)) {
			return "BUY";
		}
		int side = (int) Math.round(firstJsonNumber(tradeJson, "side"));
		if (side == 1) {
			return "SELL";
		}
		if (side == 0) {
			return "BUY";
		}
		return "";
	}

	private static String topstepxOrderStatus(String orderJson) {
		String direct = firstNonBlank(
			extractJsonString(orderJson, "status"),
			extractJsonString(orderJson, "orderStatus")
		).toUpperCase(Locale.US);
		if (!isBlank(direct)) {
			return direct;
		}
		int status = (int) Math.round(firstJsonNumber(orderJson, "status", "orderStatus"));
		if (status == 2) {
			return "FILLED";
		}
		if (status == 3) {
			return "CANCELED";
		}
		if (status == 4) {
			return "REJECTED";
		}
		return status > 0 ? String.valueOf(status) : "";
	}

	private static String topstepxOrderType(String orderJson) {
		String direct = firstNonBlank(
			extractJsonString(orderJson, "orderType"),
			extractJsonString(orderJson, "type")
		).toUpperCase(Locale.US);
		if (!isBlank(direct)) {
			return direct;
		}
		int type = (int) Math.round(firstJsonNumber(orderJson, "type", "orderType"));
		if (type == 1) {
			return "LIMIT";
		}
		if (type == 2) {
			return "MARKET";
		}
		if (type == 4) {
			return "STOP";
		}
		return type > 0 ? String.valueOf(type) : "";
	}

	private static String decimal(double value) {
		if (Math.abs(value - Math.rint(value)) < 0.000001) {
			return String.valueOf((long) Math.rint(value));
		}
		return String.format(Locale.US, "%.10f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private static MergeStats importTopstepxBarsForSymbol(
		ConnectionConfig config,
		String baseUrl,
		String token,
		boolean liveContracts,
		String symbol,
		LocalDate startDate,
		LocalDate endDate,
		String runId,
		int maxContractsPerSymbol
	) throws Exception {
		MergeStats stats = new MergeStats();
		List<TopstepContract> contracts = topstepxContractsForSymbol(baseUrl, token, liveContracts, symbol, maxContractsPerSymbol, startDate, endDate);
		stats.contractsChecked = contracts.size();
		Map<Instant, InternalBar> topstepBars = new TreeMap<Instant, InternalBar>();
		for (int index = 0; index < contracts.size(); index++) {
			TopstepContract contract = contracts.get(index);
			List<InternalBar> contractBars;
			try {
				contractBars = topstepxBarsForContract(baseUrl, token, liveContracts, contract.id, startDate, endDate);
			} catch (Exception contractError) {
				if (contract.inferred) {
					continue;
				}
				throw contractError;
			}
			if (!contractBars.isEmpty()) {
				stats.contractsWithBars++;
			}
			for (int barIndex = 0; barIndex < contractBars.size(); barIndex++) {
				InternalBar bar = contractBars.get(barIndex);
				if (!validBar(symbol, bar)) {
					stats.invalidRows++;
					continue;
				}
				InternalBar existing = topstepBars.get(bar.timestamp);
				if (existing == null || bar.volume > existing.volume) {
					topstepBars.put(bar.timestamp, bar);
				}
			}
		}
		stats.topstepRows = topstepBars.size();
		writeTopstepStage(symbol, runId, new ArrayList<InternalBar>(topstepBars.values()));

		Map<Instant, InternalBar> mergedBars = new TreeMap<Instant, InternalBar>();
		List<InternalBar> existingBars = readInternalFuturesBars(symbol);
		stats.existingRows = existingBars.size();
		for (int index = 0; index < existingBars.size(); index++) {
			InternalBar bar = existingBars.get(index);
			if (validBar(symbol, bar)) {
				mergedBars.put(bar.timestamp, bar);
			}
		}

		for (InternalBar topstepBar : topstepBars.values()) {
			InternalBar existing = mergedBars.get(topstepBar.timestamp);
			if (existing == null) {
				mergedBars.put(topstepBar.timestamp, topstepBar);
				stats.addedRows++;
			} else {
				stats.overlapRows++;
				if (materialBarDrift(existing, topstepBar, symbol)) {
					stats.driftRows++;
				}
			}
		}

		File source = new File(futuresDataDir() + "/1min/" + symbol + ".csv");
		if (source.exists()) {
			File backup = new File(futuresDataDir() + "/backups/" + runId + "/1min/" + symbol + ".csv");
			if (!backup.getParentFile().exists()) {
				backup.getParentFile().mkdirs();
			}
			Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
			stats.backupPath = backup.getPath();
		}

		List<InternalBar> merged = new ArrayList<InternalBar>(mergedBars.values());
		stats.finalRows = writeInternalFuturesBars(symbol, merged);
		if (!merged.isEmpty()) {
			stats.first = merged.get(0).timestamp.toString();
			stats.last = merged.get(merged.size() - 1).timestamp.toString();
		}
		return stats;
	}

	private static List<TopstepContract> topstepxContractsForSymbol(
		String baseUrl,
		String token,
		boolean liveContracts,
		String symbol,
		int maxContracts,
		LocalDate startDate,
		LocalDate endDate
	) throws Exception {
		String cacheKey = topstepxContractCacheKey(baseUrl, liveContracts, symbol, maxContracts, startDate, endDate);
		List<TopstepContract> cachedContracts = cachedTopstepContracts(cacheKey);
		if (!cachedContracts.isEmpty()) {
			return cachedContracts;
		}
		String body = "{\"live\":" + liveContracts + ",\"searchText\":" + jsonString(symbol) + "}";
		HttpResult result = null;
		for (int attempt = 1; attempt <= TOPSTEPX_CONTRACT_SEARCH_MAX_ATTEMPTS; attempt++) {
			result = postJson(baseUrl + "/Contract/search", body, token);
			boolean ok = result.statusCode >= 200 && result.statusCode < 300 && !isBlank(result.body);
			if (ok || !isRetriableTopstepxContractSearchFailure(result)) {
				break;
			}
			try {
				Thread.sleep(TOPSTEPX_CONTRACT_SEARCH_RETRY_BASE_MS * attempt);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (result.statusCode < 200 || result.statusCode >= 300) {
			throw new IllegalStateException("contract search failed (" + result.statusCode + "): " + summarizeBody(result.body));
		}
		if (isBlank(result.body)) {
			throw new IllegalStateException("contract search failed (" + result.statusCode + "): empty response");
		}
		String targetSymbolId = projectxSymbolId(symbol);
		List<String> objects = extractJsonArrayObjects(result.body, "contracts");
		List<TopstepContract> contracts = new ArrayList<TopstepContract>();
		for (int index = 0; index < objects.size(); index++) {
			String object = objects.get(index);
			TopstepContract contract = new TopstepContract();
			contract.id = extractJsonString(object, "id");
			contract.name = extractJsonString(object, "name");
			contract.description = extractJsonString(object, "description");
			contract.symbolId = extractJsonString(object, "symbolId");
			contract.active = jsonBoolean(object, "activeContract");
			contract.tickSize = parseDouble(extractJsonNumber(object, "tickSize"));
			contract.tickValue = parseDouble(extractJsonNumber(object, "tickValue"));
			if (!isBlank(contract.id) && targetSymbolId.equals(contract.symbolId)) {
				contracts.add(contract);
			}
		}
		appendInferredQuarterlyContracts(contracts, symbol, targetSymbolId, startDate, endDate);
		Collections.sort(contracts, new Comparator<TopstepContract>() {
			@Override
			public int compare(TopstepContract first, TopstepContract second) {
				if (first.active != second.active) {
					return first.active ? -1 : 1;
				}
				return cleanOrDefault(second.id, "").compareTo(cleanOrDefault(first.id, ""));
			}
		});
		if (contracts.size() > maxContracts) {
			contracts = new ArrayList<TopstepContract>(contracts.subList(0, maxContracts));
		}
		if (contracts.isEmpty()) {
			throw new IllegalStateException("no ProjectX contracts found for " + symbol + " / " + targetSymbolId);
		}
		cacheTopstepContracts(cacheKey, contracts);
		return copyTopstepContracts(contracts);
	}

	private static boolean isRetriableTopstepxContractSearchFailure(HttpResult result) {
		if (result == null) {
			return true;
		}
		return result.statusCode == 429 || isBlank(result.body);
	}

	private static String topstepxContractCacheKey(String baseUrl, boolean liveContracts, String symbol, int maxContracts, LocalDate startDate, LocalDate endDate) {
		return cleanOrDefault(baseUrl, "") + "|" + liveContracts + "|" + normalizeFuturesSymbol(symbol) + "|" + maxContracts + "|" + startDate + "|" + endDate;
	}

	private static List<TopstepContract> cachedTopstepContracts(String key) {
		synchronized (TOPSTEPX_CONTRACT_CACHE) {
			CachedTopstepContracts cached = TOPSTEPX_CONTRACT_CACHE.get(key);
			if (cached == null || cached.contracts == null || cached.contracts.isEmpty()) {
				return new ArrayList<TopstepContract>();
			}
			if (System.currentTimeMillis() - cached.cachedAtMillis > TOPSTEPX_CONTRACT_CACHE_TTL_MS) {
				TOPSTEPX_CONTRACT_CACHE.remove(key);
				return new ArrayList<TopstepContract>();
			}
			return copyTopstepContracts(cached.contracts);
		}
	}

	private static void cacheTopstepContracts(String key, List<TopstepContract> contracts) {
		if (contracts == null || contracts.isEmpty()) {
			return;
		}
		CachedTopstepContracts cached = new CachedTopstepContracts();
		cached.contracts = copyTopstepContracts(contracts);
		cached.cachedAtMillis = System.currentTimeMillis();
		synchronized (TOPSTEPX_CONTRACT_CACHE) {
			TOPSTEPX_CONTRACT_CACHE.put(key, cached);
		}
	}

	private static List<TopstepContract> copyTopstepContracts(List<TopstepContract> contracts) {
		List<TopstepContract> copies = new ArrayList<TopstepContract>();
		if (contracts == null) {
			return copies;
		}
		for (int index = 0; index < contracts.size(); index++) {
			TopstepContract source = contracts.get(index);
			if (source == null) {
				continue;
			}
			TopstepContract copy = new TopstepContract();
			copy.id = source.id;
			copy.name = source.name;
			copy.description = source.description;
			copy.symbolId = source.symbolId;
			copy.active = source.active;
			copy.inferred = source.inferred;
			copy.tickSize = source.tickSize;
			copy.tickValue = source.tickValue;
			copies.add(copy);
		}
		return copies;
	}

	private static void appendInferredQuarterlyContracts(
		List<TopstepContract> contracts,
		String symbol,
		String symbolId,
		LocalDate startDate,
		LocalDate endDate
	) {
		String normalized = normalizeFuturesSymbol(symbol);
		if (!"MES".equals(normalized) && !"MNQ".equals(normalized) && !"M2K".equals(normalized) && !"MYM".equals(normalized) && !"ES".equals(normalized) && !"NQ".equals(normalized)) {
			return;
		}
		String[] monthCodes = {"H", "M", "U", "Z"};
		int[] months = {3, 6, 9, 12};
		int startYear = startDate.minusMonths(6).getYear();
		int endYear = endDate.plusMonths(6).getYear();
		for (int year = startYear; year <= endYear; year++) {
			for (int index = 0; index < monthCodes.length; index++) {
				LocalDate contractMonth = LocalDate.of(year, months[index], 1);
				LocalDate usableStart = contractMonth.minusMonths(3).withDayOfMonth(1);
				LocalDate usableEnd = contractMonth.plusMonths(1).withDayOfMonth(1);
				if (usableEnd.isBefore(startDate) || usableStart.isAfter(endDate)) {
					continue;
				}
				String yearCode = String.format(Locale.US, "%02d", year % 100);
				String contractId = "CON." + symbolId + "." + monthCodes[index] + yearCode;
				if (hasContractId(contracts, contractId)) {
					continue;
				}
				TopstepContract contract = new TopstepContract();
				contract.id = contractId;
				contract.name = normalized + monthCodes[index] + String.valueOf(year % 10);
				contract.description = normalized + " inferred quarterly contract";
				contract.symbolId = symbolId;
				contract.active = false;
				contract.inferred = true;
				contract.tickSize = tickSizeForSymbol(normalized);
				contracts.add(contract);
			}
		}
	}

	private static boolean hasContractId(List<TopstepContract> contracts, String contractId) {
		for (int index = 0; index < contracts.size(); index++) {
			if (contractId.equals(contracts.get(index).id)) {
				return true;
			}
		}
		return false;
	}

	private static List<InternalBar> topstepxBarsForContract(
		String baseUrl,
		String token,
		boolean liveContracts,
		String contractId,
		LocalDate startDate,
		LocalDate endDate
	) throws Exception {
		List<InternalBar> bars = new ArrayList<InternalBar>();
		LocalDate cursor = startDate;
		while (!cursor.isAfter(endDate)) {
			LocalDate chunkEnd = cursor.plusDays(9);
			if (chunkEnd.isAfter(endDate)) {
				chunkEnd = endDate;
			}
			String startTime = cursor.toString() + "T00:00:00Z";
			String endTime = chunkEnd.plusDays(1).toString() + "T00:00:00Z";
			String body = "{"
				+ "\"contractId\":" + jsonString(contractId) + ","
				+ "\"live\":" + liveContracts + ","
				+ "\"startTime\":" + jsonString(startTime) + ","
				+ "\"endTime\":" + jsonString(endTime) + ","
				+ "\"unit\":2,"
				+ "\"unitNumber\":1,"
				+ "\"limit\":20000,"
				+ "\"includePartialBar\":false"
				+ "}";
			HttpResult result = postJson(baseUrl + "/History/retrieveBars", body, token);
			if (result.statusCode == 429) {
				Thread.sleep(2000L);
				result = postJson(baseUrl + "/History/retrieveBars", body, token);
			}
			if (result.statusCode < 200 || result.statusCode >= 300) {
				throw new IllegalStateException("history pull failed for " + contractId + " (" + result.statusCode + "): " + summarizeBody(result.body));
			}
			List<String> objects = extractJsonArrayObjects(result.body, "bars");
			for (int index = 0; index < objects.size(); index++) {
				InternalBar bar = topstepBarFromJson(objects.get(index));
				if (bar != null) {
					bars.add(bar);
				}
			}
			cursor = chunkEnd.plusDays(1);
			Thread.sleep(650L);
		}
		return bars;
	}

	private static InternalBar topstepBarFromJson(String object) {
		try {
			InternalBar bar = new InternalBar();
			bar.timestamp = parseTimestamp(extractJsonString(object, "t"));
			bar.timestampText = bar.timestamp.toString();
			bar.open = parseDouble(extractJsonNumber(object, "o"));
			bar.high = parseDouble(extractJsonNumber(object, "h"));
			bar.low = parseDouble(extractJsonNumber(object, "l"));
			bar.close = parseDouble(extractJsonNumber(object, "c"));
			bar.volume = parseDouble(extractJsonNumber(object, "v"));
			bar.vwap = typicalPrice(bar);
			return bar;
		} catch (Exception e) {
			return null;
		}
	}

	private static String testTradovate(ConnectionConfig config) throws Exception {
		if (isBlank(config.username) || isBlank(config.password)) {
			return "Tradovate username/password are missing.";
		}
		if (isBlank(config.appId) || isBlank(config.appVersion)) {
			return "Tradovate appId/appVersion are missing.";
		}

		String baseUrl = cleanOrDefault(config.baseUrl, "https://demo.tradovateapi.com/v1");
		StringBuilder body = new StringBuilder("{")
			.append("\"name\":").append(jsonString(config.username)).append(",")
			.append("\"password\":").append(jsonString(config.password)).append(",")
			.append("\"appId\":").append(jsonString(config.appId)).append(",")
			.append("\"appVersion\":").append(jsonString(config.appVersion));
		if (!isBlank(config.cid)) {
			body.append(",\"cid\":").append(jsonString(config.cid));
		}
		if (!isBlank(config.secret)) {
			body.append(",\"sec\":").append(jsonString(config.secret));
		}
		body.append("}");

		HttpResult result = postJson(baseUrl + "/auth/accesstokenrequest", body.toString(), "");
		if (result.statusCode >= 200 && result.statusCode < 300 && result.body.contains("accessToken")) {
			return "Tradovate authenticated. Next live step is account/list plus explicit order kill-switch wiring.";
		}
		return "Tradovate auth failed (" + result.statusCode + "): " + summarizeBody(result.body);
	}

	private static String testTopstepx(ConnectionConfig config) throws Exception {
		if (isBlank(config.username) || isBlank(config.apiKey)) {
			return "TopstepX username/API key are missing.";
		}

		topstepxSessionToken(config);
		return "TopstepX authenticated. Account search and contract discovery can be enabled after account ID is selected.";
	}

	private static String topstepxSessionToken(ConnectionConfig config) throws Exception {
		String baseUrl = cleanOrDefault(config.baseUrl, "https://api.topstepx.com/api");
		String body = "{"
			+ "\"userName\":" + jsonString(config.username) + ","
			+ "\"apiKey\":" + jsonString(config.apiKey)
			+ "}";

		HttpResult result = postJson(baseUrl + "/Auth/loginKey", body, "");
		String token = extractJsonString(result.body, "token");
		if (result.statusCode >= 200 && result.statusCode < 300 && jsonBoolean(result.body, "success") && !isBlank(token)) {
			return token;
		}
		throw new IllegalStateException("TopstepX auth failed (" + result.statusCode + "): " + topstepxErrorSummary(result.body));
	}

	private static HttpResult postJson(String urlString, String body, String bearerToken) throws Exception {
		HttpURLConnection conn = openConnection(urlString, "POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("accept", "text/plain, application/json");
		if (!isBlank(bearerToken)) {
			String authValue = bearerToken.trim();
			conn.setRequestProperty("Authorization", authValue.toLowerCase(Locale.US).startsWith("bearer ") ? authValue : "Bearer " + authValue);
		}
		conn.setDoOutput(true);
		try (OutputStream os = conn.getOutputStream()) {
			byte[] input = body.getBytes("UTF-8");
			os.write(input, 0, input.length);
		}
		return readResponse(conn);
	}

	private static int rewriteInternalFuturesCsv(String symbol, String csv) throws Exception {
		return writeInternalFuturesBars(symbol, parseInternalFuturesCsv(csv));
	}

	private static List<InternalBar> parseInternalFuturesCsv(String csv) throws Exception {
		String[] lines = csv == null ? new String[0] : csv.split("\\r?\\n");
		if (lines.length == 0) {
			return new ArrayList<InternalBar>();
		}

		String[] headers = splitCsvLine(lines[0]);
		int tsIndex = findHeader(headers, "ts_event", "timestamp", "time");
		int openIndex = findHeader(headers, "open", "open_px");
		int highIndex = findHeader(headers, "high", "high_px");
		int lowIndex = findHeader(headers, "low", "low_px");
		int closeIndex = findHeader(headers, "close", "close_px");
		int volumeIndex = findHeader(headers, "volume", "vol", "size");
		List<InternalBar> bars = new ArrayList<InternalBar>();

		for (int index = 1; index < lines.length; index++) {
			if (lines[index].trim().isEmpty()) {
				continue;
			}
			String[] values = splitCsvLine(lines[index]);
			InternalBar bar = toInternalBar(
				valueAt(values, tsIndex),
				valueAt(values, openIndex),
				valueAt(values, highIndex),
				valueAt(values, lowIndex),
				valueAt(values, closeIndex),
				valueAt(values, volumeIndex)
			);
			if (bar != null) {
				bars.add(bar);
			}
		}
		return bars;
	}

	static int writeInternalFuturesBars(String symbol, List<InternalBar> bars) throws Exception {
		File dir = new File(futuresDataDir() + "/1min");
		if (!dir.exists()) {
			dir.mkdirs();
		}
		Collections.sort(bars, new Comparator<InternalBar>() {
			@Override
			public int compare(InternalBar first, InternalBar second) {
				return first.timestamp.compareTo(second.timestamp);
			}
		});
		double tickSize = tickSizeForSymbol(symbol);
		enrichBars(bars, tickSize);
		writeBarFile(new File(dir, symbol + ".csv"), bars);
		writeAggregatedBars(symbol, bars, "5min", 5, tickSize);
		writeAggregatedBars(symbol, bars, "15min", 15, tickSize);
		writeAggregatedBars(symbol, bars, "1hour", 60, tickSize);
		writeSyntheticLevel2Snapshots(symbol, bars, tickSize);
		return bars.size();
	}

	private static InternalBar toInternalBar(String timestamp, String open, String high, String low, String close, String volume) {
		if (isBlank(timestamp) || isBlank(open) || isBlank(high) || isBlank(low) || isBlank(close)) {
			return null;
		}
		try {
			InternalBar bar = new InternalBar();
			bar.timestamp = parseTimestamp(timestamp);
			bar.timestampText = timestamp;
			bar.open = parseDouble(open);
			bar.high = parseDouble(high);
			bar.low = parseDouble(low);
			bar.close = parseDouble(close);
			bar.volume = parseDouble(cleanOrDefault(volume, "0"));
			bar.vwap = typicalPrice(bar);
			return bar;
		} catch (Exception e) {
			return null;
		}
	}

	private static Instant parseTimestamp(String value) {
		String trimmed = value.replace("\"", "").trim();
		if (trimmed.endsWith("Z")) {
			return Instant.parse(trimmed);
		}
		if (trimmed.contains("+")) {
			return OffsetDateTime.parse(trimmed).toInstant();
		}
		return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);
	}

	private static void writeAggregatedBars(String symbol, List<InternalBar> oneMinuteBars, String folderName, int minutes, double tickSize) throws Exception {
		List<InternalBar> aggregated = aggregateBars(oneMinuteBars, minutes);
		enrichBars(aggregated, tickSize);
		File dir = new File(futuresDataDir() + "/" + folderName);
		if (!dir.exists()) {
			dir.mkdirs();
		}
		writeBarFile(new File(dir, symbol + ".csv"), aggregated);
	}

	private static List<InternalBar> aggregateBars(List<InternalBar> bars, int minutes) {
		List<InternalBar> aggregated = new ArrayList<InternalBar>();
		InternalBar current = null;
		long currentBucket = Long.MIN_VALUE;
		double priceVolume = 0.0;
		double totalVolume = 0.0;
		long bucketSeconds = minutes * 60L;

		for (int index = 0; index < bars.size(); index++) {
			InternalBar bar = bars.get(index);
			long bucket = (bar.timestamp.getEpochSecond() / bucketSeconds) * bucketSeconds;
			if (current == null || bucket != currentBucket) {
				if (current != null) {
					current.vwap = totalVolume <= 0.0 ? typicalPrice(current) : priceVolume / totalVolume;
					aggregated.add(current);
				}
				currentBucket = bucket;
				priceVolume = 0.0;
				totalVolume = 0.0;
				current = new InternalBar();
				current.timestamp = Instant.ofEpochSecond(bucket);
				current.timestampText = current.timestamp.toString();
				current.open = bar.open;
				current.high = bar.high;
				current.low = bar.low;
				current.close = bar.close;
				current.volume = 0.0;
			}
			current.high = Math.max(current.high, bar.high);
			current.low = Math.min(current.low, bar.low);
			current.close = bar.close;
			current.volume += bar.volume;
			priceVolume += typicalPrice(bar) * Math.max(0.0, bar.volume);
			totalVolume += Math.max(0.0, bar.volume);
		}

		if (current != null) {
			current.vwap = totalVolume <= 0.0 ? typicalPrice(current) : priceVolume / totalVolume;
			aggregated.add(current);
		}
		return aggregated;
	}

	private static void enrichBars(List<InternalBar> bars, double tickSize) {
		double[] ema9 = ema(bars, 9);
		double[] ema20 = ema(bars, 20);
		double[] ema50 = ema(bars, 50);
		double[] atr14 = atr(bars, 14);
		double[] rsi14 = rsi(bars, 14);

		for (int index = 0; index < bars.size(); index++) {
			InternalBar bar = bars.get(index);
			bar.ema9 = ema9[index];
			bar.ema20 = ema20[index];
			bar.ema50 = ema50[index];
			bar.atr14 = atr14[index];
			bar.rsi14 = rsi14[index];
			bar.volumeSma20 = volumeSma(bars, index, 20);
			bar.rangeTicks = tickSize <= 0.0 ? 0.0 : (bar.high - bar.low) / tickSize;
			double range = Math.max(0.0, bar.high - bar.low);
			bar.bodyPct = range <= 0.0 ? 0.0 : (Math.abs(bar.close - bar.open) / range) * 100.0;
		}
	}

	private static double[] ema(List<InternalBar> bars, int period) {
		double[] values = new double[bars.size()];
		double multiplier = 2.0 / (period + 1.0);
		for (int index = 0; index < bars.size(); index++) {
			if (index == 0) {
				values[index] = bars.get(index).close;
			} else {
				values[index] = ((bars.get(index).close - values[index - 1]) * multiplier) + values[index - 1];
			}
		}
		return values;
	}

	private static double[] atr(List<InternalBar> bars, int period) {
		double[] values = new double[bars.size()];
		double rolling = 0.0;
		for (int index = 0; index < bars.size(); index++) {
			InternalBar bar = bars.get(index);
			double previousClose = index == 0 ? bar.close : bars.get(index - 1).close;
			double trueRange = Math.max(bar.high - bar.low, Math.max(Math.abs(bar.high - previousClose), Math.abs(bar.low - previousClose)));
			if (index < period) {
				rolling += trueRange;
				values[index] = rolling / (index + 1.0);
			} else if (index == period) {
				rolling = (rolling + trueRange) / period;
				values[index] = rolling;
			} else {
				rolling = ((values[index - 1] * (period - 1.0)) + trueRange) / period;
				values[index] = rolling;
			}
		}
		return values;
	}

	private static double[] rsi(List<InternalBar> bars, int period) {
		double[] values = new double[bars.size()];
		double gains = 0.0;
		double losses = 0.0;
		for (int index = 0; index < bars.size(); index++) {
			if (index == 0) {
				values[index] = 50.0;
				continue;
			}
			double change = bars.get(index).close - bars.get(index - 1).close;
			double gain = Math.max(0.0, change);
			double loss = Math.max(0.0, -change);
			if (index <= period) {
				gains += gain;
				losses += loss;
				values[index] = 50.0;
				continue;
			}
			if (index == period + 1) {
				gains = gains / period;
				losses = losses / period;
			} else {
				gains = ((gains * (period - 1.0)) + gain) / period;
				losses = ((losses * (period - 1.0)) + loss) / period;
			}
			if (losses == 0.0) {
				values[index] = 100.0;
			} else {
				double rs = gains / losses;
				values[index] = 100.0 - (100.0 / (1.0 + rs));
			}
		}
		return values;
	}

	private static double volumeSma(List<InternalBar> bars, int index, int period) {
		int start = Math.max(0, index - period + 1);
		double total = 0.0;
		int count = 0;
		for (int cursor = start; cursor <= index; cursor++) {
			total += bars.get(cursor).volume;
			count++;
		}
		return count == 0 ? 0.0 : total / count;
	}

	private static void writeBarFile(File output, List<InternalBar> bars) throws Exception {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
			writer.write(ENRICHED_BAR_HEADER);
			for (int index = 0; index < bars.size(); index++) {
				InternalBar bar = bars.get(index);
				writer.write(cleanTimestamp(bar.timestampText) + ","
					+ formatDecimal(bar.open) + ","
					+ formatDecimal(bar.high) + ","
					+ formatDecimal(bar.low) + ","
					+ formatDecimal(bar.close) + ","
					+ formatDecimal(bar.volume) + ","
					+ formatDecimal(bar.vwap) + ","
					+ formatDecimal(bar.ema9) + ","
					+ formatDecimal(bar.ema20) + ","
					+ formatDecimal(bar.ema50) + ","
					+ formatDecimal(bar.atr14) + ","
					+ formatDecimal(bar.rsi14) + ","
					+ formatDecimal(bar.volumeSma20) + ","
					+ formatDecimal(bar.rangeTicks) + ","
					+ formatDecimal(bar.bodyPct)
					+ "\n");
			}
		}
	}

	private static void writeSyntheticLevel2Snapshots(String symbol, List<InternalBar> bars, double tickSize) throws Exception {
		File dir = new File(futuresDataDir() + "/" + SYNTHETIC_LEVEL2_FOLDER);
		if (!dir.exists()) {
			dir.mkdirs();
		}
		double safeTick = Math.max(0.000001, tickSize);
		double previousImbalance = 0.0;
		double previousCvd = 0.0;
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dir, symbol + ".csv")))) {
			writer.write(SYNTHETIC_LEVEL2_HEADER);
			for (int index = 0; index < bars.size(); index++) {
				InternalBar bar = bars.get(index);
				double range = Math.max(safeTick, bar.high - bar.low);
				double closeLocation = clamp((bar.close - bar.low) / range, 0.0, 1.0);
				double direction = bar.close > bar.open ? 1.0 : (bar.close < bar.open ? -1.0 : 0.0);
				double bodyStrength = clamp(Math.abs(bar.close - bar.open) / range, 0.0, 1.0);
				double relativeVolume = bar.volumeSma20 <= 0.0 ? 1.0 : clamp(bar.volume / Math.max(1.0, bar.volumeSma20), 0.25, 3.0);
				double closeBias = (closeLocation - 0.5) * 2.0;
				double imbalance = clamp((closeBias * 0.45) + (direction * bodyStrength * 0.35), -0.80, 0.80);
				double signedVolume = direction == 0.0 ? closeBias * bar.volume * 0.20 : direction * bar.volume * (0.20 + (bodyStrength * 0.35));
				double tapeDelta = signedVolume * (0.60 + (relativeVolume * 0.20));
				double cvd = previousCvd + tapeDelta;
				double spreadTicks = syntheticSpreadTicks(bar, relativeVolume);
				double halfSpread = Math.max(safeTick, spreadTicks * safeTick) / 2.0;
				double bestBid = roundToTick(bar.close - halfSpread, safeTick);
				double bestAsk = roundToTick(bar.close + halfSpread, safeTick);
				if (bestAsk <= bestBid) {
					bestAsk = roundToTick(bestBid + safeTick, safeTick);
				}
				double wallDistance = Math.max(1.0, Math.min(12.0, Math.max(1.0, bar.rangeTicks) / 6.0));
				double bidWall = imbalance >= 0.18 ? Math.max(1.0, wallDistance * 0.65) : wallDistance;
				double askWall = imbalance <= -0.18 ? Math.max(1.0, wallDistance * 0.65) : wallDistance;
				double bidStacking = Math.max(0.0, imbalance - previousImbalance) * Math.max(1.0, relativeVolume * 10.0);
				double askStacking = Math.max(0.0, previousImbalance - imbalance) * Math.max(1.0, relativeVolume * 10.0);
				String absorption = syntheticAbsorption(direction, closeLocation, bodyStrength, bar);
				boolean vacuum = spreadTicks >= 3.0 && relativeVolume < 0.75 && bar.rangeTicks >= 8.0;
				String flowState = syntheticFlowState(imbalance, spreadTicks);
				writer.write(cleanTimestamp(bar.timestampText) + ","
					+ formatDecimal(bestBid) + ","
					+ formatDecimal(bestAsk) + ","
					+ formatDecimal(spreadTicks) + ","
					+ formatDecimal(imbalance) + ","
					+ formatDecimal(tapeDelta) + ","
					+ formatDecimal(cvd) + ","
					+ formatDecimal(bidWall) + ","
					+ formatDecimal(askWall) + ","
					+ formatDecimal(bidStacking) + ","
					+ formatDecimal(askStacking) + ","
					+ absorption + ","
					+ vacuum + ","
					+ flowState + ","
					+ formatDecimal(bar.open) + ","
					+ formatDecimal(bar.high) + ","
					+ formatDecimal(bar.low) + ","
					+ formatDecimal(bar.close) + ","
					+ formatDecimal(bar.volume) + ","
					+ formatDecimal(bar.rangeTicks) + ","
					+ formatDecimal(bar.bodyPct) + ","
					+ "SYNTHETIC_FROM_1MIN_CANDLE"
					+ "\n");
				previousImbalance = imbalance;
				previousCvd = cvd;
			}
		}
	}

	private static double syntheticSpreadTicks(InternalBar bar, double relativeVolume) {
		double rangeTicks = Math.max(0.0, bar.rangeTicks);
		if (relativeVolume < 0.55 || rangeTicks >= 80.0) {
			return 3.0;
		}
		if (relativeVolume < 0.80 || rangeTicks >= 45.0) {
			return 2.0;
		}
		return 1.0;
	}

	private static String syntheticAbsorption(double direction, double closeLocation, double bodyStrength, InternalBar bar) {
		if (bar == null || bodyStrength > 0.55) {
			return "NONE";
		}
		if (direction >= 0.0 && closeLocation >= 0.70) {
			return "BID_ABSORPTION";
		}
		if (direction <= 0.0 && closeLocation <= 0.30) {
			return "ASK_ABSORPTION";
		}
		return "NONE";
	}

	private static String syntheticFlowState(double imbalance, double spreadTicks) {
		if (spreadTicks >= 5.0) {
			return "SPREAD_WIDE";
		}
		if (imbalance >= 0.25) {
			return "BID_HEAVY";
		}
		if (imbalance <= -0.25) {
			return "ASK_HEAVY";
		}
		return "BALANCED";
	}

	static List<InternalBar> readInternalFuturesBars(String symbol) throws Exception {
		List<InternalBar> bars = new ArrayList<InternalBar>();
		File source = new File(futuresDataDir() + "/1min/" + symbol + ".csv");
		if (!source.exists()) {
			return bars;
		}
		try (BufferedReader reader = new BufferedReader(new FileReader(source))) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				return bars;
			}
			String[] headers = splitCsvLine(headerLine);
			int tsIndex = findHeader(headers, "timestamp", "ts_event", "time");
			int openIndex = findHeader(headers, "open", "open_px");
			int highIndex = findHeader(headers, "high", "high_px");
			int lowIndex = findHeader(headers, "low", "low_px");
			int closeIndex = findHeader(headers, "close", "close_px");
			int volumeIndex = findHeader(headers, "volume", "vol", "size");
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.trim().isEmpty()) {
					continue;
				}
				String[] values = splitCsvLine(line);
				InternalBar bar = toInternalBar(
					valueAt(values, tsIndex),
					valueAt(values, openIndex),
					valueAt(values, highIndex),
					valueAt(values, lowIndex),
					valueAt(values, closeIndex),
					valueAt(values, volumeIndex)
				);
				if (bar != null) {
					bars.add(bar);
				}
			}
		}
		return bars;
	}

	private static LocalDate firstBarDateUtc(List<InternalBar> bars) {
		if (bars == null || bars.isEmpty()) {
			return null;
		}
		Instant first = null;
		for (int index = 0; index < bars.size(); index++) {
			InternalBar bar = bars.get(index);
			if (bar == null || bar.timestamp == null) {
				continue;
			}
			if (first == null || bar.timestamp.isBefore(first)) {
				first = bar.timestamp;
			}
		}
		return first == null ? null : first.atZone(ZoneOffset.UTC).toLocalDate();
	}

	private static String backupFuturesBars(String symbol, String runId) throws Exception {
		File source = new File(futuresDataDir() + "/1min/" + symbol + ".csv");
		if (!source.exists()) {
			return "";
		}
		File backup = new File(futuresDataDir() + "/backups/" + runId + "/1min/" + symbol + ".csv");
		if (!backup.getParentFile().exists()) {
			backup.getParentFile().mkdirs();
		}
		Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return backup.getPath();
	}

	private static void writeTopstepStage(String symbol, String runId, List<InternalBar> bars) throws Exception {
		File dir = new File(futuresDataDir() + "/topstepx_stage/" + runId + "/1min");
		if (!dir.exists()) {
			dir.mkdirs();
		}
		Collections.sort(bars, new Comparator<InternalBar>() {
			@Override
			public int compare(InternalBar first, InternalBar second) {
				return first.timestamp.compareTo(second.timestamp);
			}
		});
		List<InternalBar> stageBars = new ArrayList<InternalBar>(bars);
		enrichBars(stageBars, tickSizeForSymbol(symbol));
		writeBarFile(new File(dir, symbol + ".csv"), stageBars);
	}

	private static boolean validBar(String symbol, InternalBar bar) {
		if (bar == null || bar.timestamp == null) {
			return false;
		}
		if (bar.high < bar.low || bar.open < bar.low || bar.open > bar.high || bar.close < bar.low || bar.close > bar.high) {
			return false;
		}
		double tickSize = tickSizeForSymbol(symbol);
		return tickAligned(bar.open, tickSize)
			&& tickAligned(bar.high, tickSize)
			&& tickAligned(bar.low, tickSize)
			&& tickAligned(bar.close, tickSize);
	}

	private static boolean tickAligned(double price, double tickSize) {
		if (tickSize <= 0.0) {
			return true;
		}
		double ticks = price / tickSize;
		return Math.abs(ticks - Math.round(ticks)) < 0.0001;
	}

	private static boolean materialBarDrift(InternalBar first, InternalBar second, String symbol) {
		double threshold = Math.max(tickSizeForSymbol(symbol), 0.000001);
		return Math.abs(first.open - second.open) > threshold
			|| Math.abs(first.high - second.high) > threshold
			|| Math.abs(first.low - second.low) > threshold
			|| Math.abs(first.close - second.close) > threshold;
	}

	private static String cleanTimestamp(String timestamp) {
		return isBlank(timestamp) ? "" : timestamp.replace("\"", "").trim();
	}

	private static double clamp(double value, double min, double max) {
		if (value < min) {
			return min;
		}
		if (value > max) {
			return max;
		}
		return value;
	}

	private static double roundToTick(double value, double tickSize) {
		double safeTick = Math.max(0.000001, tickSize);
		return Math.round(value / safeTick) * safeTick;
	}

	private static double typicalPrice(InternalBar bar) {
		return (bar.high + bar.low + bar.close) / 3.0;
	}

	private static double tickSizeForSymbol(String symbol) {
		String normalized = normalizeFuturesSymbol(symbol);
		if ("MES".equals(normalized) || "ES".equals(normalized) || "MNQ".equals(normalized) || "NQ".equals(normalized)) {
			return 0.25;
		}
		if ("M2K".equals(normalized) || "MGC".equals(normalized) || "GC".equals(normalized)) {
			return 0.10;
		}
		if ("MYM".equals(normalized)) {
			return 1.00;
		}
		return 0.01;
	}

	private static double parseDouble(String value) {
		if (isBlank(value)) {
			return 0.0;
		}
		try {
			return Double.parseDouble(value.replace("\"", "").trim());
		} catch (Exception e) {
			return 0.0;
		}
	}

	private static String formatDecimal(double value) {
		return String.format(Locale.US, "%.8f", value);
	}

	private static String[] splitCsvLine(String line) {
		return line.split(",", -1);
	}

	private static int findHeader(String[] headers, String... candidates) {
		for (String candidate : candidates) {
			for (int index = 0; index < headers.length; index++) {
				if (candidate.equalsIgnoreCase(headers[index].replace("\"", "").trim())) {
					return index;
				}
			}
		}
		return -1;
	}

	private static String valueAt(String[] values, int index) {
		if (index < 0 || index >= values.length) {
			return "";
		}
		return values[index].replace("\"", "").trim();
	}

	private static HttpURLConnection openConnection(String urlString, String method) throws Exception {
		URL url = new URL(urlString);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(method);
		conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
		conn.setReadTimeout(READ_TIMEOUT_MS);
		return conn;
	}

	private static HttpResult readResponse(HttpURLConnection conn) throws Exception {
		HttpResult result = new HttpResult();
		result.statusCode = conn.getResponseCode();
		InputStream stream = result.statusCode >= 200 && result.statusCode < 400 ? conn.getInputStream() : conn.getErrorStream();
		result.body = readStream(stream);
		return result;
	}

	private static String readStream(InputStream stream) throws Exception {
		if (stream == null) {
			return "";
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
			StringBuilder body = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line).append("\n");
			}
			return body.toString();
		}
	}

	private static String formPair(String key, String value) throws Exception {
		return URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value == null ? "" : value, "UTF-8");
	}

	private static void ensureDefaultConnection(String provider) {
		if (exists(provider)) {
			ensureDefaultSymbolMappings(provider);
			return;
		}
		ConnectionConfig config = defaultConnection(provider);
		config.updatedAt = Instant.now().toString();
		String sql = "INSERT INTO FuturesConnections (provider, enabled, baseUrl, environment, username, apiKey, password, secret, appId, appVersion, cid, accountId, accountSpec, dataset, schema, symbols, marketHubUrl, userHubUrl, lastTestStatus, lastTestMessage, lastTestAt, updatedAt) "
			+ "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			bindConnection(pstmt, config);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static void ensureConfiguredTopstepAccountListed() {
		ConnectionConfig config = loadConnection(TOPSTEPX);
		String accountId = cleanTopstepAccountId(config.accountId);
		if (isBlank(accountId) || findTopstepAccount(accountId) != null) {
			return;
		}
		String now = Instant.now().toString();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement("INSERT INTO TopstepAccounts (accountId, name, active, createdAt, updatedAt) VALUES (?, ?, 1, ?, ?)")) {
			pstmt.setString(1, accountId);
			pstmt.setString(2, "Connected Account");
			pstmt.setString(3, now);
			pstmt.setString(4, now);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static void ensureDefaultSymbolMappings(String provider) {
		ConnectionConfig defaults = defaultConnection(provider);
		String sql = "SELECT symbols FROM FuturesConnections WHERE provider = ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, provider);
			ResultSet rs = pstmt.executeQuery();
			if (!rs.next()) {
				return;
			}
			String currentSymbols = cleanOrDefault(rs.getString("symbols"), "");
			String mergedSymbols = appendMissingSymbols(currentSymbols, defaults.symbols);
			if (!mergedSymbols.equals(currentSymbols)) {
				try (PreparedStatement update = conn.prepareStatement("UPDATE FuturesConnections SET symbols = ?, updatedAt = ? WHERE provider = ?")) {
					update.setString(1, mergedSymbols);
					update.setString(2, Instant.now().toString());
					update.setString(3, provider);
					update.executeUpdate();
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static boolean exists(String provider) {
		String sql = "SELECT provider FROM FuturesConnections WHERE provider = ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, provider);
			ResultSet rs = pstmt.executeQuery();
			return rs.next();
		} catch (SQLException e) {
			return false;
		}
	}

	private static ConnectionConfig loadConnection(String provider) {
		String normalizedProvider = normalizeProvider(provider);
		ConnectionConfig fallback = defaultConnection(normalizedProvider);
		String sql = "SELECT * FROM FuturesConnections WHERE provider = ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, normalizedProvider);
			ResultSet rs = pstmt.executeQuery();
			if (!rs.next()) {
				return fallback;
			}
			ConnectionConfig config = new ConnectionConfig();
			config.provider = normalizedProvider;
			config.enabled = rs.getInt("enabled") == 1;
			config.baseUrl = cleanOrDefault(rs.getString("baseUrl"), fallback.baseUrl);
			config.environment = cleanOrDefault(rs.getString("environment"), fallback.environment);
			config.username = cleanOrDefault(rs.getString("username"), "");
			config.apiKey = cleanOrDefault(rs.getString("apiKey"), "");
			config.password = cleanOrDefault(rs.getString("password"), "");
			config.secret = cleanOrDefault(rs.getString("secret"), "");
			config.appId = cleanOrDefault(rs.getString("appId"), fallback.appId);
			config.appVersion = cleanOrDefault(rs.getString("appVersion"), fallback.appVersion);
			config.cid = cleanOrDefault(rs.getString("cid"), fallback.cid);
			config.accountId = cleanOrDefault(rs.getString("accountId"), "");
			config.accountSpec = cleanOrDefault(rs.getString("accountSpec"), "");
			config.dataset = cleanOrDefault(rs.getString("dataset"), fallback.dataset);
			config.schema = cleanOrDefault(rs.getString("schema"), fallback.schema);
			config.symbols = cleanOrDefault(rs.getString("symbols"), fallback.symbols);
			config.marketHubUrl = cleanOrDefault(rs.getString("marketHubUrl"), fallback.marketHubUrl);
			config.userHubUrl = cleanOrDefault(rs.getString("userHubUrl"), fallback.userHubUrl);
			config.lastTestStatus = cleanOrDefault(rs.getString("lastTestStatus"), "not_tested");
			config.lastTestMessage = cleanOrDefault(rs.getString("lastTestMessage"), "");
			config.lastTestAt = cleanOrDefault(rs.getString("lastTestAt"), "");
			config.updatedAt = cleanOrDefault(rs.getString("updatedAt"), "");
			return config;
		} catch (SQLException e) {
			e.printStackTrace();
			return fallback;
		}
	}

	private static ConnectionConfig defaultConnection(String provider) {
		ConnectionConfig config = new ConnectionConfig();
		config.provider = normalizeProvider(provider);
		config.enabled = false;
		config.baseUrl = "";
		config.environment = "DEMO";
		config.username = "";
		config.apiKey = "";
		config.password = "";
		config.secret = "";
		config.appId = "trading_bot";
		config.appVersion = "1.0";
		config.cid = "";
		config.accountId = "";
		config.accountSpec = "";
		config.dataset = "GLBX.MDP3";
		config.schema = "ohlcv-1m";
		config.symbols = DEFAULT_FUTURES_SYMBOLS;
		config.marketHubUrl = "";
		config.userHubUrl = "";
		config.lastTestStatus = "not_tested";
		config.lastTestMessage = "";
		config.lastTestAt = "";
		config.updatedAt = "";

		if (TRADOVATE.equals(config.provider)) {
			config.baseUrl = "https://demo.tradovateapi.com/v1";
			config.environment = "DEMO";
		} else if (TOPSTEPX.equals(config.provider)) {
			config.baseUrl = "https://api.topstepx.com/api";
			config.environment = "PRACTICE_COMBINE";
			config.marketHubUrl = "https://rtc.topstepx.com/hubs/market";
			config.userHubUrl = "https://rtc.topstepx.com/hubs/user";
		}

		return config;
	}

	private static List<TopstepAccount> loadTopstepAccounts() {
		List<TopstepAccount> accounts = new ArrayList<TopstepAccount>();
		String sql = "SELECT accountId, name, active, createdAt, updatedAt FROM TopstepAccounts ORDER BY active DESC, name COLLATE NOCASE ASC, accountId ASC";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql);
			 ResultSet rs = pstmt.executeQuery()) {
			while (rs.next()) {
				TopstepAccount account = new TopstepAccount();
				account.accountId = cleanTopstepAccountId(rs.getString("accountId"));
				account.name = cleanOrDefault(rs.getString("name"), "Topstep " + account.accountId);
				account.active = rs.getInt("active") == 1;
				account.createdAt = cleanOrDefault(rs.getString("createdAt"), "");
				account.updatedAt = cleanOrDefault(rs.getString("updatedAt"), "");
				accounts.add(account);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return accounts;
	}

	private static TopstepAccount findTopstepAccount(String accountId) {
		String cleanAccountId = cleanTopstepAccountId(accountId);
		if (isBlank(cleanAccountId)) {
			return null;
		}
		String sql = "SELECT accountId, name, active, createdAt, updatedAt FROM TopstepAccounts WHERE accountId = ?";
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, cleanAccountId);
			try (ResultSet rs = pstmt.executeQuery()) {
				if (!rs.next()) {
					return null;
				}
				TopstepAccount account = new TopstepAccount();
				account.accountId = cleanAccountId;
				account.name = cleanOrDefault(rs.getString("name"), "Topstep " + cleanAccountId);
				account.active = rs.getInt("active") == 1;
				account.createdAt = cleanOrDefault(rs.getString("createdAt"), "");
				account.updatedAt = cleanOrDefault(rs.getString("updatedAt"), "");
				return account;
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return null;
		}
	}

	private static void replaceSavedTopstepAccountId(String oldAccountId, String newAccountId, String name) {
		String cleanOldAccountId = cleanTopstepAccountId(oldAccountId);
		String cleanNewAccountId = cleanTopstepAccountId(newAccountId);
		if (isBlank(cleanOldAccountId) || isBlank(cleanNewAccountId) || cleanOldAccountId.equals(cleanNewAccountId)) {
			return;
		}
		String cleanName = isBlank(name) ? "Topstep " + cleanNewAccountId : name.trim();
		String now = Instant.now().toString();
		Connection conn = null;
		try {
			conn = DatabaseManager.getConnection();
			conn.setAutoCommit(false);
			try (PreparedStatement removeExisting = conn.prepareStatement("DELETE FROM TopstepAccounts WHERE accountId = ? AND accountId <> ?")) {
				removeExisting.setString(1, cleanNewAccountId);
				removeExisting.setString(2, cleanOldAccountId);
				removeExisting.executeUpdate();
			}
			try (PreparedStatement updateAccount = conn.prepareStatement("UPDATE TopstepAccounts SET accountId = ?, name = ?, active = 1, updatedAt = ? WHERE accountId = ?")) {
				updateAccount.setString(1, cleanNewAccountId);
				updateAccount.setString(2, cleanName);
				updateAccount.setString(3, now);
				updateAccount.setString(4, cleanOldAccountId);
				updateAccount.executeUpdate();
			}
			try (PreparedStatement clearOtherActive = conn.prepareStatement("UPDATE TopstepAccounts SET active = 0, updatedAt = ? WHERE accountId <> ?")) {
				clearOtherActive.setString(1, now);
				clearOtherActive.setString(2, cleanNewAccountId);
				clearOtherActive.executeUpdate();
			}
			try (PreparedStatement updateConnection = conn.prepareStatement("UPDATE FuturesConnections SET accountId = ?, updatedAt = ? WHERE provider = ?")) {
				updateConnection.setString(1, cleanNewAccountId);
				updateConnection.setString(2, now);
				updateConnection.setString(3, TOPSTEPX);
				updateConnection.executeUpdate();
			}
			conn.commit();
		} catch (SQLException e) {
			if (conn != null) {
				try {
					conn.rollback();
				} catch (SQLException rollbackError) {
					rollbackError.printStackTrace();
				}
			}
			e.printStackTrace();
		} finally {
			if (conn != null) {
				try {
					conn.setAutoCommit(true);
					conn.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static String topstepAccountsJson(List<TopstepAccount> accounts) {
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < accounts.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			json.append(topstepAccountJson(accounts.get(index)));
		}
		json.append("]");
		return json.toString();
	}

	private static String topstepAccountJson(TopstepAccount account) {
		return "{"
			+ "\"accountId\":" + jsonString(account.accountId) + ","
			+ "\"name\":" + jsonString(account.name) + ","
			+ "\"active\":" + account.active + ","
			+ "\"createdAt\":" + jsonString(account.createdAt) + ","
			+ "\"updatedAt\":" + jsonString(account.updatedAt)
			+ "}";
	}

	private static void bindConnection(PreparedStatement pstmt, ConnectionConfig config) throws SQLException {
		pstmt.setString(1, config.provider);
		pstmt.setInt(2, config.enabled ? 1 : 0);
		pstmt.setString(3, config.baseUrl);
		pstmt.setString(4, config.environment);
		pstmt.setString(5, config.username);
		pstmt.setString(6, config.apiKey);
		pstmt.setString(7, config.password);
		pstmt.setString(8, config.secret);
		pstmt.setString(9, config.appId);
		pstmt.setString(10, config.appVersion);
		pstmt.setString(11, config.cid);
		pstmt.setString(12, config.accountId);
		pstmt.setString(13, config.accountSpec);
		pstmt.setString(14, config.dataset);
		pstmt.setString(15, config.schema);
		pstmt.setString(16, config.symbols);
		pstmt.setString(17, config.marketHubUrl);
		pstmt.setString(18, config.userHubUrl);
		pstmt.setString(19, config.lastTestStatus);
		pstmt.setString(20, config.lastTestMessage);
		pstmt.setString(21, config.lastTestAt);
		pstmt.setString(22, config.updatedAt);
	}

	private static void saveTestResult(String provider, String status, String message) {
		String sql = "UPDATE FuturesConnections SET lastTestStatus = ?, lastTestMessage = ?, lastTestAt = ?, updatedAt = ? WHERE provider = ?";
		String now = Instant.now().toString();
		try (Connection conn = DatabaseManager.getConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setString(1, status);
			pstmt.setString(2, message);
			pstmt.setString(3, now);
			pstmt.setString(4, now);
			pstmt.setString(5, normalizeProvider(provider));
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static String toJson(ConnectionConfig config) {
		return "{"
			+ "\"provider\":" + jsonString(config.provider) + ","
			+ "\"enabled\":" + config.enabled + ","
			+ "\"baseUrl\":" + jsonString(config.baseUrl) + ","
			+ "\"environment\":" + jsonString(config.environment) + ","
			+ "\"username\":" + jsonString(config.username) + ","
			+ "\"hasApiKey\":" + !isBlank(config.apiKey) + ","
			+ "\"apiKeyPreview\":" + jsonString(mask(config.apiKey)) + ","
			+ "\"hasPassword\":" + !isBlank(config.password) + ","
			+ "\"hasSecret\":" + !isBlank(config.secret) + ","
			+ "\"secretPreview\":" + jsonString(mask(config.secret)) + ","
			+ "\"appId\":" + jsonString(config.appId) + ","
			+ "\"appVersion\":" + jsonString(config.appVersion) + ","
			+ "\"cid\":" + jsonString(config.cid) + ","
			+ "\"accountId\":" + jsonString(config.accountId) + ","
			+ "\"accountSpec\":" + jsonString(config.accountSpec) + ","
			+ "\"dataset\":" + jsonString(config.dataset) + ","
			+ "\"schema\":" + jsonString(config.schema) + ","
			+ "\"symbols\":" + jsonString(config.symbols) + ","
			+ "\"marketHubUrl\":" + jsonString(config.marketHubUrl) + ","
			+ "\"userHubUrl\":" + jsonString(config.userHubUrl) + ","
			+ "\"lastTestStatus\":" + jsonString(config.lastTestStatus) + ","
			+ "\"lastTestMessage\":" + jsonString(config.lastTestMessage) + ","
			+ "\"lastTestAt\":" + jsonString(config.lastTestAt) + ","
			+ "\"updatedAt\":" + jsonString(config.updatedAt) + ","
			+ "\"topstepAccounts\":" + (TOPSTEPX.equals(config.provider) ? topstepAccountsJson(loadTopstepAccounts()) : "[]")
			+ "}";
	}

	private static String cleanTopstepAccountId(String accountId) {
		return cleanOrDefault(accountId, "").replaceAll("[^0-9]", "");
	}

	private static String normalizeProvider(String provider) {
		if (provider == null) {
			return TOPSTEPX;
		}
		String normalized = provider.trim().toUpperCase();
		if ("PROJECTX".equals(normalized) || "TOPSTEP".equals(normalized)) {
			return TOPSTEPX;
		}
		if ("TRADOVATE_DIRECT".equals(normalized)) {
			return TRADOVATE;
		}
		if (!TRADOVATE.equals(normalized) && !TOPSTEPX.equals(normalized)) {
			return TOPSTEPX;
		}
		return normalized;
	}

	private static String normalizeFuturesSymbol(String symbol) {
		if (symbol == null || symbol.trim().isEmpty()) {
			return "MNQ";
		}
		String normalized = symbol.trim().toUpperCase();
		if (!"MES".equals(normalized) && !"MNQ".equals(normalized) && !"M2K".equals(normalized) && !"MYM".equals(normalized) && !"ES".equals(normalized) && !"NQ".equals(normalized)
			&& !"MGC".equals(normalized) && !"MCL".equals(normalized) && !"GC".equals(normalized)) {
			return "MNQ";
		}
		return normalized;
	}

	private static List<String> normalizeSymbolList(String symbols) {
		List<String> values = new ArrayList<String>();
		String source = cleanOrDefault(symbols, "MNQ,NQ,M2K,MYM,MGC,MCL,ES,MES,GC");
		String[] parts = source.split(",");
		for (int index = 0; index < parts.length; index++) {
			String normalized = normalizeFuturesSymbol(parts[index]);
			if (!values.contains(normalized)) {
				values.add(normalized);
			}
		}
		if (values.isEmpty()) {
			values.add("MNQ");
		}
		return values;
	}

	private static LocalDate parseDate(String value, LocalDate defaultDate) {
		try {
			if (value == null || value.trim().isEmpty()) {
				return defaultDate;
			}
			return LocalDate.parse(value.trim());
		} catch (Exception e) {
			return defaultDate;
		}
	}

	private static String projectxSymbolId(String symbol) {
		String normalized = normalizeFuturesSymbol(symbol);
		if ("NQ".equals(normalized)) {
			return "F.US.ENQ";
		}
		if ("ES".equals(normalized)) {
			return "F.US.EP";
		}
		if ("MCL".equals(normalized)) {
			return "F.US.MCLE";
		}
		if ("GC".equals(normalized)) {
			return "F.US.GCE";
		}
		return "F.US." + normalized;
	}

	private static String preferredContinuousSymbol(String symbol) {
		String normalized = normalizeFuturesSymbol(symbol);
		if ("MGC".equals(normalized) || "GC".equals(normalized)) {
			return normalized + ".v.0";
		}
		return normalized + ".c.0";
	}

	private static String appendMissingSymbols(String currentSymbols, String defaultSymbols) {
		String merged = cleanOrDefault(currentSymbols, "");
		if (isBlank(defaultSymbols)) {
			return merged;
		}
		String[] defaults = defaultSymbols.split(",");
		for (String value : defaults) {
			String trimmed = value.trim();
			if (trimmed.isEmpty() || containsSymbolMapping(merged, trimmed)) {
				continue;
			}
			merged = isBlank(merged) ? trimmed : merged + "," + trimmed;
		}
		return merged;
	}

	private static boolean containsSymbolMapping(String symbols, String candidate) {
		if (isBlank(symbols) || isBlank(candidate)) {
			return false;
		}
		String normalizedCandidate = candidate.trim().toUpperCase(Locale.US);
		String[] values = symbols.split(",");
		for (String value : values) {
			if (normalizedCandidate.equals(value.trim().toUpperCase(Locale.US))) {
				return true;
			}
		}
		return false;
	}

	private static String secretOrExisting(String incoming, String existing) {
		if (incoming == null || incoming.trim().isEmpty() || "__KEEP__".equals(incoming.trim())) {
			return cleanOrDefault(existing, "");
		}
		return incoming.trim();
	}

	private static String cleanOrDefault(String value, String defaultValue) {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue == null ? "" : defaultValue;
		}
		return value.trim();
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			if (!isBlank(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static String summarizeBody(String body) {
		if (body == null || body.trim().isEmpty()) {
			return "empty response";
		}
		String clean = body.replaceAll("\\s+", " ").trim();
		return clean.length() > 260 ? clean.substring(0, 260) + "..." : clean;
	}

	private static String safeMessage(String message) {
		return message == null || message.trim().isEmpty() ? "unknown error" : summarizeBody(message);
	}

	private static String syncResponseJson(HttpResult result, String arrayName) {
		String body = result == null ? "" : cleanOrDefault(result.body, "");
		if (result == null || result.statusCode < 200 || result.statusCode >= 300) {
			return "{\"success\":false,\"statusCode\":" + (result == null ? 0 : result.statusCode)
				+ ",\"message\":" + jsonString(summarizeBody(body)) + "}";
		}
		String trimmed = body.trim();
		if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
			return trimmed;
		}
		return "{\"success\":true,\"" + arrayName + "\":[]}";
	}

	private static String extractJsonString(String body, String fieldName) {
		if (body == null || fieldName == null) {
			return "";
		}
		String needle = "\"" + fieldName + "\"";
		int fieldIndex = body.indexOf(needle);
		if (fieldIndex < 0) {
			return "";
		}
		int colonIndex = body.indexOf(":", fieldIndex + needle.length());
		if (colonIndex < 0) {
			return "";
		}
		int cursor = colonIndex + 1;
		while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
			cursor++;
		}
		if (cursor >= body.length() || body.charAt(cursor) != '"') {
			return "";
		}
		StringBuilder value = new StringBuilder();
		boolean escaped = false;
		for (int index = cursor + 1; index < body.length(); index++) {
			char ch = body.charAt(index);
			if (escaped) {
				value.append(ch);
				escaped = false;
				continue;
			}
			if (ch == '\\') {
				escaped = true;
				continue;
			}
			if (ch == '"') {
				return value.toString();
			}
			value.append(ch);
		}
		return "";
	}

	private static boolean jsonBoolean(String body, String fieldName) {
		if (body == null || fieldName == null) {
			return false;
		}
		String needle = "\"" + fieldName + "\"";
		int fieldIndex = body.indexOf(needle);
		if (fieldIndex < 0) {
			return false;
		}
		int colonIndex = body.indexOf(":", fieldIndex + needle.length());
		if (colonIndex < 0) {
			return false;
		}
		int cursor = colonIndex + 1;
		while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
			cursor++;
		}
		return cursor + 4 <= body.length() && "true".equalsIgnoreCase(body.substring(cursor, cursor + 4));
	}

	private static String topstepxErrorSummary(String body) {
		String errorCode = extractJsonNumber(body, "errorCode");
		String errorMessage = extractJsonString(body, "errorMessage");
		if (!isBlank(errorCode) || !isBlank(errorMessage)) {
			return "success=false"
				+ (isBlank(errorCode) ? "" : ", errorCode=" + errorCode)
				+ (isBlank(errorMessage) ? "" : ", errorMessage=" + errorMessage);
		}
		return summarizeBody(body);
	}

	private static String extractJsonNumber(String body, String fieldName) {
		if (body == null || fieldName == null) {
			return "";
		}
		String needle = "\"" + fieldName + "\"";
		int fieldIndex = body.indexOf(needle);
		if (fieldIndex < 0) {
			return "";
		}
		int colonIndex = body.indexOf(":", fieldIndex + needle.length());
		if (colonIndex < 0) {
			return "";
		}
		int cursor = colonIndex + 1;
		while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
			cursor++;
		}
		StringBuilder value = new StringBuilder();
		while (cursor < body.length()) {
			char ch = body.charAt(cursor);
			if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '.') {
				value.append(ch);
				cursor++;
				continue;
			}
			break;
		}
		return value.toString();
	}

	private static int jsonArrayObjectCount(String body, String arrayName) {
		if (body == null || arrayName == null) {
			return 0;
		}
		String needle = "\"" + arrayName + "\"";
		int fieldIndex = body.indexOf(needle);
		if (fieldIndex < 0) {
			return 0;
		}
		int arrayStart = body.indexOf("[", fieldIndex + needle.length());
		if (arrayStart < 0) {
			return 0;
		}
		int depth = 0;
		int objectDepth = 0;
		int count = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int index = arrayStart; index < body.length(); index++) {
			char ch = body.charAt(index);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (ch == '\\') {
				escaped = inString;
				continue;
			}
			if (ch == '"') {
				inString = !inString;
				continue;
			}
			if (inString) {
				continue;
			}
			if (ch == '[') {
				depth++;
			} else if (ch == ']') {
				depth--;
				if (depth == 0) {
					break;
				}
			} else if (ch == '{') {
				if (depth == 1 && objectDepth == 0) {
					count++;
				}
				objectDepth++;
			} else if (ch == '}') {
				objectDepth = Math.max(0, objectDepth - 1);
			}
		}
		return count;
	}

	private static List<String> extractJsonArrayObjects(String body, String arrayName) {
		List<String> objects = new ArrayList<String>();
		if (body == null || arrayName == null) {
			return objects;
		}
		String needle = "\"" + arrayName + "\"";
		int fieldIndex = body.indexOf(needle);
		if (fieldIndex < 0) {
			return objects;
		}
		int arrayStart = body.indexOf("[", fieldIndex + needle.length());
		if (arrayStart < 0) {
			return objects;
		}
		int arrayDepth = 0;
		int objectDepth = 0;
		int objectStart = -1;
		boolean inString = false;
		boolean escaped = false;
		for (int index = arrayStart; index < body.length(); index++) {
			char ch = body.charAt(index);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (ch == '\\') {
				escaped = inString;
				continue;
			}
			if (ch == '"') {
				inString = !inString;
				continue;
			}
			if (inString) {
				continue;
			}
			if (ch == '[') {
				arrayDepth++;
			} else if (ch == ']') {
				arrayDepth--;
				if (arrayDepth == 0) {
					break;
				}
			} else if (ch == '{') {
				if (arrayDepth == 1 && objectDepth == 0) {
					objectStart = index;
				}
				objectDepth++;
			} else if (ch == '}') {
				objectDepth = Math.max(0, objectDepth - 1);
				if (arrayDepth == 1 && objectDepth == 0 && objectStart >= 0) {
					objects.add(body.substring(objectStart, index + 1));
					objectStart = -1;
				}
			}
		}
		return objects;
	}

	private static String mask(String value) {
		if (isBlank(value)) {
			return "";
		}
		String trimmed = value.trim();
		if (trimmed.length() <= 4) {
			return "****";
		}
		return "****" + trimmed.substring(trimmed.length() - 4);
	}

	private static String futuresDataDir() {
		return RuntimePaths.futuresDataDir();
	}

	private static String jsonString(String value) {
		if (value == null) {
			return "\"\"";
		}
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
	}
}
