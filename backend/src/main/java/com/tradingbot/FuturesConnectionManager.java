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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class FuturesConnectionManager {
	private static final String DATABENTO = "DATABENTO";
	private static final String TRADOVATE = "TRADOVATE";
	private static final String TOPSTEPX = "TOPSTEPX";
	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 120000;
	private static final String FUTURES_DATA_DIR = "market_data/futures";
	private static final String ENRICHED_BAR_HEADER = "timestamp,open,high,low,close,volume,vwap,ema9,ema20,ema50,atr14,rsi14,volume_sma20,range_ticks,body_pct\n";
	private static final String DEFAULT_FUTURES_SYMBOLS = "MES.c.0,MNQ.c.0,M2K.c.0,ES.c.0,NQ.c.0,MGC.v.0,GC.v.0";

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

	private static class HttpResult {
		private int statusCode;
		private String body;
	}

	private static class InternalBar {
		private Instant timestamp;
		private String timestampText;
		private double open;
		private double high;
		private double low;
		private double close;
		private double volume;
		private double vwap;
		private double ema9;
		private double ema20;
		private double ema50;
		private double atr14;
		private double rsi14;
		private double volumeSma20;
		private double rangeTicks;
		private double bodyPct;
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
		private int overlapRows;
		private int driftRows;
		private int invalidRows;
		private int finalRows;
		private int contractsChecked;
		private int contractsWithBars;
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
		} catch (SQLException e) {
			e.printStackTrace();
		}

		ensureDefaultConnection(DATABENTO);
		ensureDefaultConnection(TRADOVATE);
		ensureDefaultConnection(TOPSTEPX);
	}

	public static String getConnectionsJson() {
		initializeStore();
		StringBuilder json = new StringBuilder("[");
		String[] providers = {DATABENTO, TRADOVATE, TOPSTEPX};
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
			+ "\"provider\":\"DATABENTO\","
			+ "\"name\":\"Databento Historical Futures Data\","
			+ "\"purpose\":\"Backtest-grade CME futures bars and optional order-book/trade data.\","
			+ "\"requiredFields\":[\"apiKey\",\"dataset\",\"schema\",\"symbols\"],"
			+ "\"defaultDataset\":\"GLBX.MDP3\","
			+ "\"defaultSchema\":\"ohlcv-1m\","
			+ "\"supportedSchemas\":[\"ohlcv-1m\"],"
			+ "\"docs\":\"https://databento.com/docs/api-reference-historical\""
			+ "},"
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
			if (DATABENTO.equals(normalizedProvider)) {
				message = testDatabento(config);
			} else if (TRADOVATE.equals(normalizedProvider)) {
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

	public static String importDatabentoBars(String symbol, String startDate, String endDate) {
		return importDatabentoBars(symbol, startDate, endDate, "");
	}

	public static String importDatabentoBars(String symbol, String startDate, String endDate, String requestedSchema) {
		initializeStore();
		ConnectionConfig config = loadConnection(DATABENTO);
		String key = cleanOrDefault(config.apiKey, System.getenv("DATABENTO_API_KEY"));
		if (isBlank(key)) {
			return "{\"success\":false,\"message\":\"Databento API key is missing.\",\"rows\":0}";
		}

		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		String databentoSymbol = resolveDatabentoSymbol(config, normalizedSymbol);
		String baseUrl = cleanOrDefault(config.baseUrl, "https://hist.databento.com/v0");
		String dataset = cleanOrDefault(config.dataset, "GLBX.MDP3");
		String schemaRequest = cleanOrDefault(requestedSchema, "ohlcv-1m");
		String schema = normalizeDatabentoSchema(schemaRequest);
		if (!"ohlcv-1m".equals(schema)) {
			return "{\"success\":false,\"message\":"
				+ jsonString("Only OHLCV 1-minute Databento imports are enabled. The 1-second import path was removed after the execution audit showed no useful difference for the current strategy.")
				+ ",\"rows\":0}";
		}
		String requestStart = cleanOrDefault(startDate, "2024-01-01") + "T00:00";
		String requestEnd = cleanOrDefault(endDate, "2024-12-31") + "T23:59";

		try {
			HttpURLConnection conn = openConnection(baseUrl + "/timeseries.get_range", "POST");
			String auth = Base64.getEncoder().encodeToString((key + ":").getBytes("UTF-8"));
			conn.setRequestProperty("Authorization", "Basic " + auth);
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			conn.setRequestProperty("accept", "text/csv, text/plain");
			conn.setDoOutput(true);

			String body = formPair("dataset", dataset)
				+ "&" + formPair("symbols", databentoSymbol)
				+ "&" + formPair("schema", schema)
				+ "&" + formPair("start", requestStart)
				+ "&" + formPair("end", requestEnd)
				+ "&" + formPair("stype_in", "continuous")
				+ "&" + formPair("stype_out", "instrument_id")
				+ "&" + formPair("encoding", "csv")
				+ "&" + formPair("pretty_px", "true")
				+ "&" + formPair("pretty_ts", "true")
				+ "&" + formPair("map_symbols", "true");
			try (OutputStream os = conn.getOutputStream()) {
				byte[] input = body.getBytes("UTF-8");
				os.write(input, 0, input.length);
			}

			HttpResult result = readResponse(conn);
			if (result.statusCode < 200 || result.statusCode >= 300) {
				if (result.statusCode == 422 && result.body != null && result.body.contains("dataset_unavailable_range")) {
					String suggestedEndDate = LocalDate.now(ZoneOffset.UTC).minusDays(2).toString();
					return "{\"success\":false,\"message\":"
						+ jsonString("Databento says part of that date range is too recent or not licensed yet. This importer requests full calendar days, so set End Date to " + suggestedEndDate + " or earlier, then try again.")
						+ ",\"rows\":0}";
				}
				return "{\"success\":false,\"message\":" + jsonString("Databento import failed (" + result.statusCode + "): " + summarizeBody(result.body)) + ",\"rows\":0}";
			}

			int rows = writeInternalFuturesCsv(normalizedSymbol, result.body);
			String storagePath = "market_data/futures/1min/" + normalizedSymbol + ".csv";
			String generatedMessage = "generated 5-minute, 15-minute, and 1-hour futures files.";
			saveTestResult(DATABENTO, "connected", "Databento imported " + rows + " " + normalizedSymbol + " " + schema + " bars from " + databentoSymbol + " and " + generatedMessage);
			return "{\"success\":true,\"message\":"
				+ jsonString("Databento imported " + rows + " " + normalizedSymbol + " " + schema + " bars, " + generatedMessage)
				+ ",\"symbol\":" + jsonString(normalizedSymbol)
				+ ",\"databentoSymbol\":" + jsonString(databentoSymbol)
				+ ",\"schema\":" + jsonString(schema)
				+ ",\"rows\":" + rows
				+ ",\"path\":" + jsonString(storagePath)
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("Databento import failed: " + safeMessage(e.getMessage())) + ",\"rows\":0}";
		}
	}

	public static String rebuildDerivedFuturesData(String symbol) {
		String normalizedSymbol = normalizeFuturesSymbol(symbol);
		File source = new File(FUTURES_DATA_DIR + "/1min/" + normalizedSymbol + ".csv");
		if (!source.exists()) {
			return "{\"success\":false,\"message\":"
				+ jsonString("No 1-minute futures file exists for " + normalizedSymbol + ". Import Databento bars first.")
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
			int rows = writeInternalFuturesCsv(normalizedSymbol, csv.toString());
			return "{\"success\":true,\"message\":"
				+ jsonString("Rebuilt " + normalizedSymbol + " futures data into enriched 1-minute, 5-minute, 15-minute, and 1-hour files without calling Databento.")
				+ ",\"symbol\":" + jsonString(normalizedSymbol)
				+ ",\"rows\":" + rows
				+ ",\"path\":" + jsonString(FUTURES_DATA_DIR + "/1min/" + normalizedSymbol + ".csv")
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
			String message = "TopstepX historical merge completed for " + symbolList.size() + " symbol(s). Existing Databento rows were preserved on overlapping timestamps; Topstep filled missing timestamps only.";
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
			String[] symbols = {"MNQ", "NQ", "M2K", "MGC", "ES", "MES", "GC"};
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
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before placing practice orders.") + "}";
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
			appendBracketObjects(body, contract, entryPrice, stopPrice, targetPrice);
			body.append("}");

			HttpResult order = postJson(realtimeConfig.baseUrl + "/Order/place", body.toString(), activeToken);
			boolean ok = order.statusCode >= 200 && order.statusCode < 300 && jsonBoolean(order.body, "success");
			String orderId = extractJsonNumber(order.body, "orderId");
			return "{"
				+ "\"success\":" + ok + ","
				+ "\"message\":" + jsonString(ok ? "ProjectX practice order submitted." : "ProjectX order submit failed: " + topstepxErrorSummary(order.body)) + ","
				+ "\"statusCode\":" + order.statusCode + ","
				+ "\"orderId\":" + (isBlank(orderId) ? "0" : orderId) + ","
				+ "\"accountId\":" + jsonString(accountId) + ","
				+ "\"symbol\":" + jsonString(normalizedSymbol) + ","
				+ "\"contractId\":" + jsonString(contract.contractId) + ","
				+ "\"contractName\":" + jsonString(contract.name) + ","
				+ "\"request\":" + body.toString() + ","
				+ "\"response\":" + syncResponseJson(order, "order")
				+ "}";
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("ProjectX practice order submit failed: " + safeMessage(e.getMessage())) + "}";
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
			return "{\"success\":false,\"message\":" + jsonString("TopstepX configured account must be " + accountId + " before flattening practice positions.") + "}";
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
				+ "\"message\":" + jsonString(success ? "TopstepX practice flatten/cancel sweep completed." : "TopstepX practice flatten/cancel sweep needs attention.") + ","
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
			return "{\"success\":false,\"message\":" + jsonString("TopstepX practice flatten/cancel failed: " + safeMessage(e.getMessage())) + "}";
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
		int stopTicks = stopPrice > 0.0 ? Math.max(1, (int) Math.round(Math.abs(entryPrice - stopPrice) / contract.tickSize)) : 0;
		int targetTicks = targetPrice > 0.0 ? Math.max(1, (int) Math.round(Math.abs(targetPrice - entryPrice) / contract.tickSize)) : 0;
		if (stopTicks > 0) {
			body.append(",\"stopLossBracket\":{\"ticks\":").append(stopTicks).append(",\"type\":4}");
		}
		if (targetTicks > 0) {
			body.append(",\"takeProfitBracket\":{\"ticks\":").append(targetTicks).append(",\"type\":1}");
		}
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

		File source = new File(FUTURES_DATA_DIR + "/1min/" + symbol + ".csv");
		if (source.exists()) {
			File backup = new File(FUTURES_DATA_DIR + "/backups/" + runId + "/1min/" + symbol + ".csv");
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
		String body = "{\"live\":" + liveContracts + ",\"searchText\":" + jsonString(symbol) + "}";
		HttpResult result = postJson(baseUrl + "/Contract/search", body, token);
		if (result.statusCode < 200 || result.statusCode >= 300) {
			throw new IllegalStateException("contract search failed (" + result.statusCode + "): " + summarizeBody(result.body));
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
			return new ArrayList<TopstepContract>(contracts.subList(0, maxContracts));
		}
		if (contracts.isEmpty()) {
			throw new IllegalStateException("no ProjectX contracts found for " + symbol + " / " + targetSymbolId);
		}
		return contracts;
	}

	private static void appendInferredQuarterlyContracts(
		List<TopstepContract> contracts,
		String symbol,
		String symbolId,
		LocalDate startDate,
		LocalDate endDate
	) {
		String normalized = normalizeFuturesSymbol(symbol);
		if (!"MES".equals(normalized) && !"MNQ".equals(normalized) && !"M2K".equals(normalized) && !"ES".equals(normalized) && !"NQ".equals(normalized)) {
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

	private static String testDatabento(ConnectionConfig config) throws Exception {
		String key = cleanOrDefault(config.apiKey, System.getenv("DATABENTO_API_KEY"));
		if (isBlank(key)) {
			return "Databento API key is missing. Add DATABENTO_API_KEY or save the key in Futures Live.";
		}

		String baseUrl = cleanOrDefault(config.baseUrl, "https://hist.databento.com/v0");
		HttpURLConnection conn = openConnection(baseUrl + "/metadata.list_datasets", "GET");
		String auth = Base64.getEncoder().encodeToString((key + ":").getBytes("UTF-8"));
		conn.setRequestProperty("Authorization", "Basic " + auth);
		HttpResult result = readResponse(conn);
		if (result.statusCode >= 200 && result.statusCode < 300) {
			return result.body.contains("GLBX.MDP3")
				? "Databento connected. GLBX.MDP3 futures dataset is visible."
				: "Databento connected, but GLBX.MDP3 was not visible in the metadata response.";
		}
		return "Databento auth failed (" + result.statusCode + "): " + summarizeBody(result.body);
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

	private static int writeInternalFuturesCsv(String symbol, String databentoCsv) throws Exception {
		File dir = new File(FUTURES_DATA_DIR + "/1min");
		if (!dir.exists()) {
			dir.mkdirs();
		}
		File output = new File(dir, symbol + ".csv");
		String[] lines = databentoCsv == null ? new String[0] : databentoCsv.split("\\r?\\n");
		if (lines.length == 0) {
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
				writer.write(ENRICHED_BAR_HEADER);
			}
			return 0;
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

		return writeInternalFuturesBars(symbol, bars);
	}

	private static int writeInternalFuturesBars(String symbol, List<InternalBar> bars) throws Exception {
		File dir = new File(FUTURES_DATA_DIR + "/1min");
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
		File dir = new File(FUTURES_DATA_DIR + "/" + folderName);
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

	private static List<InternalBar> readInternalFuturesBars(String symbol) throws Exception {
		List<InternalBar> bars = new ArrayList<InternalBar>();
		File source = new File(FUTURES_DATA_DIR + "/1min/" + symbol + ".csv");
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

	private static void writeTopstepStage(String symbol, String runId, List<InternalBar> bars) throws Exception {
		File dir = new File(FUTURES_DATA_DIR + "/topstepx_stage/" + runId + "/1min");
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
		return 0.01;
	}

	private static String normalizeDatabentoSchema(String schema) {
		String normalized = cleanOrDefault(schema, "ohlcv-1m").trim().toLowerCase(Locale.US);
		if ("ohlcv-1m".equals(normalized)) {
			return normalized;
		}
		return normalized;
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

		if (DATABENTO.equals(config.provider)) {
			config.baseUrl = "https://hist.databento.com/v0";
			config.environment = "HISTORICAL";
		} else if (TRADOVATE.equals(config.provider)) {
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
			+ "\"updatedAt\":" + jsonString(config.updatedAt)
			+ "}";
	}

	private static String normalizeProvider(String provider) {
		if (provider == null) {
			return DATABENTO;
		}
		String normalized = provider.trim().toUpperCase();
		if ("PROJECTX".equals(normalized) || "TOPSTEP".equals(normalized)) {
			return TOPSTEPX;
		}
		if ("DATABENTO_HISTORICAL".equals(normalized)) {
			return DATABENTO;
		}
		if ("TRADOVATE_DIRECT".equals(normalized)) {
			return TRADOVATE;
		}
		if (!DATABENTO.equals(normalized) && !TRADOVATE.equals(normalized) && !TOPSTEPX.equals(normalized)) {
			return DATABENTO;
		}
		return normalized;
	}

	private static String normalizeFuturesSymbol(String symbol) {
		if (symbol == null || symbol.trim().isEmpty()) {
			return "MNQ";
		}
		String normalized = symbol.trim().toUpperCase();
		if (!"MES".equals(normalized) && !"MNQ".equals(normalized) && !"M2K".equals(normalized) && !"ES".equals(normalized) && !"NQ".equals(normalized)
			&& !"MGC".equals(normalized) && !"GC".equals(normalized)) {
			return "MNQ";
		}
		return normalized;
	}

	private static List<String> normalizeSymbolList(String symbols) {
		List<String> values = new ArrayList<String>();
		String source = cleanOrDefault(symbols, "MNQ,NQ,M2K,MGC,ES,MES,GC");
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
		if ("GC".equals(normalized)) {
			return "F.US.GCE";
		}
		return "F.US." + normalized;
	}

	private static String resolveDatabentoSymbol(ConnectionConfig config, String symbol) {
		String preferred = preferredContinuousSymbol(symbol);
		if (!isBlank(config.symbols)) {
			String[] values = config.symbols.split(",");
			for (String value : values) {
				String trimmed = value.trim();
				if (trimmed.equalsIgnoreCase(preferred)) {
					return trimmed;
				}
			}
			for (String value : values) {
				String trimmed = value.trim();
				if (trimmed.toUpperCase().startsWith(symbol + ".")) {
					return trimmed;
				}
			}
		}
		return preferred;
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

	private static String jsonString(String value) {
		if (value == null) {
			return "\"\"";
		}
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
	}
}
