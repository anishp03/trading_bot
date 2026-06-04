package com.tradingbot;

import com.microsoft.signalr.HubConnection;
import com.microsoft.signalr.HubConnectionBuilder;
import com.microsoft.signalr.HubConnectionState;
import com.microsoft.signalr.TransportEnum;
import io.reactivex.rxjava3.core.Single;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProjectXRealtimeManager {
	private static final DateTimeFormatter DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static HubConnection userConnection;
	private static HubConnection marketConnection;
	private static RealtimeRuntime runtime = new RealtimeRuntime();
	private static volatile boolean storeInitialized = false;
	private static final long MARKET_QUOTE_PERSIST_INTERVAL_MS = 1000L;
	private static final long MARKET_TRADE_PERSIST_INTERVAL_MS = 350L;
	private static final long[] RECONNECT_DELAYS_MS = new long[] { 5000L, 15000L, 30000L, 60000L };
	private static final ScheduledExecutorService REALTIME_RECONNECT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "projectx-realtime-reconnect");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<String, Long> lastPersistedMarketEvents = new LinkedHashMap<String, Long>();
	private static boolean shutdownRequested = false;
	private static boolean suppressClosedCallbacks = false;
	private static boolean reconnectScheduled = false;
	private static int reconnectAttempt = 0;

	private static class RealtimeRuntime {
		private boolean running;
		private String dataMode = "IDLE";
		private String accountId = "";
		private String symbols = "";
		private String startedAt = "";
		private String lastEventAt = "";
		private String lastMessage = "ProjectX realtime is idle.";
		private boolean includeDepth;
		private boolean userHubConnected;
		private boolean marketHubConnected;
		private String userHubStatus = "IDLE";
		private String marketHubStatus = "IDLE";
		private String userHubMessage = "";
		private String marketHubMessage = "";
		private int eventCount;
		private final Map<String, String> contractSymbols = new LinkedHashMap<String, String>();
		private final List<String> subscribedContracts = new ArrayList<String>();
	}

	private static class RealtimeHubSession {
		private HubConnection user;
		private HubConnection market;
		private boolean userHubConnected;
		private boolean marketHubConnected;
		private String userHubMessage = "";
		private String marketHubMessage = "";
	}

	public static void initializeStore() {
		if (storeInitialized) {
			return;
		}
		synchronized (ProjectXRealtimeManager.class) {
			if (storeInitialized) {
				return;
			}
			try (Connection conn = DatabaseManager.getConnection();
				 Statement stmt = conn.createStatement()) {
				configureRealtimeConnection(conn);
				stmt.execute("PRAGMA journal_mode=WAL");
				stmt.execute("PRAGMA synchronous=NORMAL");
				stmt.execute(
					"CREATE TABLE IF NOT EXISTS FuturesLiveRealtimeEvents ("
						+ "realtimeEventID INTEGER PRIMARY KEY AUTOINCREMENT, "
						+ "hub TEXT, eventType TEXT, accountId TEXT, contractId TEXT, symbol TEXT, payloadJson TEXT, receivedAt TEXT"
						+ ")"
				);
				stmt.execute("CREATE INDEX IF NOT EXISTS idx_futures_realtime_symbol_time ON FuturesLiveRealtimeEvents (hub, symbol, receivedAt)");
				stmt.execute("CREATE INDEX IF NOT EXISTS idx_futures_realtime_symbol_id ON FuturesLiveRealtimeEvents (hub, symbol, realtimeEventID)");
				storeInitialized = true;
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}

	public static Connection openRealtimeConnection() throws SQLException {
		initializeStore();
		Connection conn = DatabaseManager.getConnection();
		configureRealtimeConnection(conn);
		return conn;
	}

	private static void configureRealtimeConnection(Connection conn) throws SQLException {
		try (Statement stmt = conn.createStatement()) {
			stmt.execute("PRAGMA busy_timeout=5000");
		}
	}

	public static String getStatusJson() {
		initializeStore();
		RealtimeRuntime copy;
		synchronized (ProjectXRealtimeManager.class) {
			copy = copyRuntime(runtime);
		}
		return "{"
			+ "\"running\":" + copy.running + ","
			+ "\"dataMode\":" + jsonString(copy.dataMode) + ","
			+ "\"accountId\":" + jsonString(copy.accountId) + ","
			+ "\"configuredAccountId\":" + jsonString(FuturesConnectionManager.getTopstepxConfiguredAccountId()) + ","
			+ "\"symbols\":" + jsonString(copy.symbols) + ","
			+ "\"startedAt\":" + jsonString(copy.startedAt) + ","
			+ "\"lastEventAt\":" + jsonString(copy.lastEventAt) + ","
			+ "\"includeDepth\":" + copy.includeDepth + ","
			+ "\"userHubConnected\":" + copy.userHubConnected + ","
			+ "\"marketHubConnected\":" + copy.marketHubConnected + ","
			+ "\"userHubStatus\":" + jsonString(copy.userHubStatus) + ","
			+ "\"marketHubStatus\":" + jsonString(copy.marketHubStatus) + ","
			+ "\"userHubMessage\":" + jsonString(copy.userHubMessage) + ","
			+ "\"marketHubMessage\":" + jsonString(copy.marketHubMessage) + ","
			+ "\"eventCount\":" + copy.eventCount + ","
			+ "\"lastMessage\":" + jsonString(copy.lastMessage) + ","
			+ "\"subscribedContracts\":" + stringListJson(copy.subscribedContracts) + ","
			+ "\"recentEvents\":" + getEventsJson(25)
			+ "}";
	}

	public static boolean isRunning() {
		synchronized (ProjectXRealtimeManager.class) {
			return runtime.running;
		}
	}

	public static boolean isDepthIncluded() {
		synchronized (ProjectXRealtimeManager.class) {
			return runtime.includeDepth;
		}
	}

	public static String currentDataMode() {
		synchronized (ProjectXRealtimeManager.class) {
			return cleanOrDefault(runtime.dataMode, "IDLE");
		}
	}

	public static String currentAccountId() {
		synchronized (ProjectXRealtimeManager.class) {
			return cleanOrDefault(runtime.accountId, "");
		}
	}

	public static String currentLastEventAt() {
		synchronized (ProjectXRealtimeManager.class) {
			return cleanOrDefault(runtime.lastEventAt, "");
		}
	}

	public static String currentStartedAt() {
		synchronized (ProjectXRealtimeManager.class) {
			return cleanOrDefault(runtime.startedAt, "");
		}
	}

	public static String currentLastMessage() {
		synchronized (ProjectXRealtimeManager.class) {
			return cleanOrDefault(runtime.lastMessage, "");
		}
	}

	public static String getPlanJson(String symbols, boolean includeDepth) {
		initializeStore();
		String configuredAccountId = FuturesConnectionManager.getTopstepxConfiguredAccountId();
		List<String> symbolList = normalizeSymbols(symbols);
		StringBuilder subscriptions = new StringBuilder("[");
		for (int index = 0; index < symbolList.size(); index++) {
			String symbol = symbolList.get(index);
			if (index > 0) {
				subscriptions.append(",");
			}
			subscriptions.append("{")
				.append("\"symbol\":").append(jsonString(symbol)).append(",")
				.append("\"symbolId\":").append(jsonString(FuturesConnectionManager.projectxSymbolIdForRealtime(symbol))).append(",")
				.append("\"marketSubscriptions\":[\"SubscribeContractQuotes\",\"SubscribeContractTrades\"");
			if (includeDepth) {
				subscriptions.append(",\"SubscribeContractMarketDepth\"");
			}
			subscriptions.append("],")
				.append("\"contractResolution\":\"Contract/search is performed only when realtime start is explicitly confirmed.\"")
				.append("}");
		}
		subscriptions.append("]");
		return "{"
			+ "\"provider\":\"TOPSTEPX\","
			+ "\"accountId\":" + jsonString(configuredAccountId) + ","
			+ "\"configuredAccountId\":" + jsonString(configuredAccountId) + ","
			+ "\"accountOk\":" + (configuredAccountId.length() > 0) + ","
			+ "\"includeDepth\":" + includeDepth + ","
			+ "\"userSubscriptions\":[\"SubscribeAccounts\",\"SubscribeOrders\",\"SubscribePositions\",\"SubscribeTrades\"],"
			+ "\"marketSubscriptions\":" + subscriptions + ","
			+ "\"willSubmitOrders\":false,"
			+ "\"message\":\"Realtime plan is read-only. Starting it authenticates to ProjectX and subscribes to SignalR updates only.\""
			+ "}";
	}

	public static String startReadOnly(String symbols, boolean includeDepth, boolean confirmed) {
		initializeStore();
		if (!confirmed) {
			return "{\"success\":false,\"message\":\"Confirm read-only ProjectX realtime start before opening TopstepX hubs.\",\"status\":" + getStatusJson() + "}";
		}
		boolean reconnectNeeded = false;
		synchronized (ProjectXRealtimeManager.class) {
			if (runtime.running) {
				reconnectNeeded = !runtime.marketHubConnected;
			}
		}
		if (reconnectNeeded) {
			scheduleReconnect("ProjectX realtime start requested while the market hub is disconnected.");
			return "{\"success\":true,\"message\":\"ProjectX realtime is already running; reconnect is scheduled.\",\"status\":" + getStatusJson() + "}";
		}
		synchronized (ProjectXRealtimeManager.class) {
			if (runtime.running) {
				return "{\"success\":true,\"message\":\"ProjectX realtime is already running.\",\"status\":" + getStatusJson() + "}";
			}
		}

		FuturesConnectionManager.TopstepxRealtimeConfig config;
		List<FuturesConnectionManager.TopstepxContractInfo> contracts;
		try {
			config = FuturesConnectionManager.createTopstepxRealtimeConfig();
			contracts = FuturesConnectionManager.resolveTopstepxRealtimeContracts(config, symbols);
			if (contracts.isEmpty()) {
				return "{\"success\":false,\"message\":\"No ProjectX contracts resolved for realtime subscriptions.\",\"status\":" + getStatusJson() + "}";
			}
		} catch (Exception e) {
			return "{\"success\":false,\"message\":" + jsonString("ProjectX realtime preflight failed: " + safeMessage(e.getMessage())) + ",\"status\":" + getStatusJson() + "}";
		}

		RealtimeRuntime next = new RealtimeRuntime();
		next.accountId = config.accountId;
		next.symbols = joinContractSymbols(contracts);
		next.includeDepth = includeDepth;
		next.startedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		next.lastMessage = "Connecting to ProjectX read-only realtime hubs.";
		for (int index = 0; index < contracts.size(); index++) {
			FuturesConnectionManager.TopstepxContractInfo contract = contracts.get(index);
			next.contractSymbols.put(contract.contractId, contract.symbol);
			next.subscribedContracts.add(contract.contractId);
		}
		synchronized (ProjectXRealtimeManager.class) {
			runtime = next;
			shutdownRequested = false;
			suppressClosedCallbacks = false;
			reconnectScheduled = false;
			reconnectAttempt = 0;
		}

		try {
			RealtimeHubSession hubs = connectRealtimeHubs(config, contracts, includeDepth);
			next.marketHubConnected = hubs.marketHubConnected;
			next.marketHubStatus = hubs.marketHubConnected ? "CONNECTED" : "UNAVAILABLE";
			next.marketHubMessage = hubs.marketHubMessage;
			next.userHubConnected = hubs.userHubConnected;
			next.userHubStatus = hubs.userHubConnected ? "CONNECTED" : "UNAVAILABLE";
			next.userHubMessage = hubs.userHubMessage;

			synchronized (ProjectXRealtimeManager.class) {
				userConnection = hubs.user;
				marketConnection = hubs.market;
				next.running = true;
				next.dataMode = hubs.userHubConnected ? "PROJECTX_SIGNALR" : "PROJECTX_SIGNALR_MARKET_ONLY";
				next.lastMessage = hubs.userHubConnected
					? "ProjectX read-only realtime hubs are connected. Order submission remains disabled."
					: "ProjectX market prices are connected. User/account hub is unavailable, but order submission remains disabled.";
				runtime = next;
			}
			return "{\"success\":true,\"message\":"
				+ jsonString(hubs.userHubConnected
					? "ProjectX read-only realtime started. No broker order path was enabled."
					: "ProjectX market-price realtime started. User/account hub is unavailable; no broker order path was enabled.")
				+ ",\"status\":" + getStatusJson() + "}";
		} catch (Exception e) {
			synchronized (ProjectXRealtimeManager.class) {
				suppressClosedCallbacks = true;
			}
			stopConnectionsQuietly();
			synchronized (ProjectXRealtimeManager.class) {
				suppressClosedCallbacks = false;
				runtime.running = true;
				runtime.dataMode = "RECONNECTING";
				runtime.userHubConnected = false;
				runtime.marketHubConnected = false;
				runtime.userHubStatus = "RECONNECTING";
				runtime.marketHubStatus = "RECONNECTING";
				runtime.userHubMessage = "Waiting for ProjectX user hub reconnect.";
				runtime.marketHubMessage = "Waiting for ProjectX market hub reconnect.";
				runtime.lastMessage = "ProjectX realtime start is waiting for reconnect: " + safeMessage(e.getMessage());
				shutdownRequested = false;
				reconnectScheduled = false;
			}
			scheduleReconnect("Initial ProjectX realtime connection failed: " + safeMessage(e.getMessage()));
			return "{\"success\":true,\"message\":" + jsonString("ProjectX realtime is waiting for the market data connection to resume. Reconnect is scheduled and subscriptions will be renewed automatically.") + ",\"status\":" + getStatusJson() + "}";
		}
	}

	public static String restartReadOnly(String symbols, boolean includeDepth, boolean confirmed) {
		initializeStore();
		if (!confirmed) {
			return "{\"success\":false,\"message\":\"Confirm ProjectX realtime restart before opening TopstepX hubs.\",\"status\":" + getStatusJson() + "}";
		}
		synchronized (ProjectXRealtimeManager.class) {
			shutdownRequested = true;
			reconnectScheduled = false;
		}
		stopConnectionsQuietly();
		synchronized (ProjectXRealtimeManager.class) {
			runtime.running = false;
			runtime.dataMode = "RESTARTING";
			runtime.userHubConnected = false;
			runtime.marketHubConnected = false;
			runtime.userHubStatus = "RESTARTING";
			runtime.marketHubStatus = "RESTARTING";
			runtime.lastMessage = "Restarting ProjectX realtime feed.";
		}
		return startReadOnly(symbols, includeDepth, true);
	}

	public static String stopReadOnly() {
		initializeStore();
		synchronized (ProjectXRealtimeManager.class) {
			shutdownRequested = true;
			reconnectScheduled = false;
			reconnectAttempt = 0;
		}
		stopConnectionsQuietly();
		synchronized (ProjectXRealtimeManager.class) {
			runtime.running = false;
			runtime.dataMode = "STOPPED";
			runtime.userHubConnected = false;
			runtime.marketHubConnected = false;
			runtime.userHubStatus = "STOPPED";
			runtime.marketHubStatus = "STOPPED";
			runtime.startedAt = "";
			runtime.userHubMessage = "ProjectX user hub stopped.";
			runtime.marketHubMessage = "ProjectX market hub stopped.";
			runtime.lastMessage = "ProjectX realtime stopped.";
		}
		return "{\"success\":true,\"message\":\"ProjectX realtime stopped.\",\"status\":" + getStatusJson() + "}";
	}

	public static String getEventsJson(int limit) {
		initializeStore();
		int safeLimit = Math.max(1, Math.min(500, limit));
		StringBuilder json = new StringBuilder("[");
		String sql = "SELECT * FROM FuturesLiveRealtimeEvents ORDER BY realtimeEventID DESC LIMIT ?";
		try (Connection conn = openRealtimeConnection();
			 PreparedStatement pstmt = conn.prepareStatement(sql)) {
			pstmt.setInt(1, safeLimit);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					if (json.length() > 1) {
						json.append(",");
					}
					json.append("{")
						.append("\"id\":").append(rs.getInt("realtimeEventID")).append(",")
						.append("\"hub\":").append(jsonString(rs.getString("hub"))).append(",")
						.append("\"eventType\":").append(jsonString(rs.getString("eventType"))).append(",")
						.append("\"accountId\":").append(jsonString(rs.getString("accountId"))).append(",")
						.append("\"contractId\":").append(jsonString(rs.getString("contractId"))).append(",")
						.append("\"symbol\":").append(jsonString(rs.getString("symbol"))).append(",")
						.append("\"receivedAt\":").append(jsonString(rs.getString("receivedAt")))
						.append("}");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		json.append("]");
		return json.toString();
	}

	private static RealtimeHubSession connectRealtimeHubs(
		FuturesConnectionManager.TopstepxRealtimeConfig config,
		List<FuturesConnectionManager.TopstepxContractInfo> contracts,
		boolean includeDepth
	) {
		Single<String> tokenProvider = Single.defer(() -> Single.just(config.token));
		RealtimeHubSession hubs = new RealtimeHubSession();
		HubConnection nextMarketConnection = buildProjectXHub(config.marketHubUrl, tokenProvider);
		registerMarketHandlers(nextMarketConnection);
		startHubWithRetry(nextMarketConnection, "market");
		subscribeMarketHub(nextMarketConnection, contracts, includeDepth);
		hubs.market = nextMarketConnection;
		hubs.marketHubConnected = true;
		hubs.marketHubMessage = "ProjectX market hub connected and subscriptions renewed.";

		HubConnection nextUserConnection = null;
		try {
			nextUserConnection = buildProjectXHub(config.userHubUrl, tokenProvider);
			registerUserHandlers(nextUserConnection);
			startHubWithRetry(nextUserConnection, "user");
			subscribeUserHub(nextUserConnection, config.accountId);
			hubs.user = nextUserConnection;
			hubs.userHubConnected = true;
			hubs.userHubMessage = "ProjectX user account hub connected and subscriptions renewed.";
		} catch (Exception userHubError) {
			stopConnectionQuietly(nextUserConnection);
			hubs.user = null;
			hubs.userHubConnected = false;
			hubs.userHubMessage = "ProjectX user account hub unavailable: " + safeMessage(userHubError.getMessage()) + ". Market prices remain connected.";
		}
		return hubs;
	}

	private static HubConnection buildProjectXHub(String hubUrl, Single<String> tokenProvider) {
		return HubConnectionBuilder
			.create(stripAccessToken(hubUrl))
			.shouldSkipNegotiate(true)
			.withTransport(TransportEnum.WEBSOCKETS)
			.withAccessTokenProvider(tokenProvider)
			.withHandshakeResponseTimeout(10000)
			.withServerTimeout(60000)
			.withKeepAliveInterval(15000)
			.build();
	}

	private static void registerUserHandlers(HubConnection connection) {
		connection.<Map>on("GatewayUserAccount", data -> recordRealtimeEvent("user", "GatewayUserAccount", accountIdFromPayload(data), "", "", jsonValue(data)), Map.class);
		connection.<Map>on("GatewayUserOrder", data -> recordRealtimeEvent("user", "GatewayUserOrder", accountIdFromPayload(data), stringValue(data.get("contractId")), "", jsonValue(data)), Map.class);
		connection.<Map>on("GatewayUserPosition", data -> recordRealtimeEvent("user", "GatewayUserPosition", accountIdFromPayload(data), stringValue(data.get("contractId")), "", jsonValue(data)), Map.class);
		connection.<Map>on("GatewayUserTrade", data -> recordRealtimeEvent("user", "GatewayUserTrade", accountIdFromPayload(data), stringValue(data.get("contractId")), "", jsonValue(data)), Map.class);
		connection.<Object>on("GatewayLogout", data -> recordRealtimeEvent("user", "GatewayLogout", "", "", "", jsonValue(data)), Object.class);
		connection.onClosed(error -> handleUserHubClosed(error));
	}

	private static void registerMarketHandlers(HubConnection connection) {
		connection.<String, Object>on("GatewayQuote", (contractId, data) -> recordRealtimeEvent("market", "GatewayQuote", "", contractId, symbolForContract(contractId), jsonValue(data)), String.class, Object.class);
		connection.<String, Object>on("GatewayTrade", (contractId, data) -> recordRealtimeEvent("market", "GatewayTrade", "", contractId, symbolForContract(contractId), jsonValue(data)), String.class, Object.class);
		connection.<String, Object>on("GatewayDepth", (contractId, data) -> recordRealtimeEvent("market", "GatewayDepth", "", contractId, symbolForContract(contractId), jsonValue(data)), String.class, Object.class);
		connection.onClosed(error -> handleMarketHubClosed(error));
	}

	private static void handleUserHubClosed(Exception error) {
		String message = error == null ? "ProjectX user hub closed." : "ProjectX user hub closed: " + safeMessage(error.getMessage());
		boolean shouldReconnect = false;
		synchronized (ProjectXRealtimeManager.class) {
			if (shutdownRequested || suppressClosedCallbacks) {
				return;
			}
			runtime.userHubConnected = false;
			runtime.userHubStatus = "DISCONNECTED";
			runtime.userHubMessage = message;
			if (runtime.running) {
				runtime.dataMode = runtime.marketHubConnected ? "PROJECTX_SIGNALR_MARKET_ONLY" : "RECONNECTING";
				runtime.lastMessage = message + " Reconnect will renew the token and resubscribe.";
				shouldReconnect = true;
			} else {
				runtime.dataMode = "DISCONNECTED";
				runtime.lastMessage = message;
			}
		}
		if (shouldReconnect) {
			scheduleReconnect(message);
		}
	}

	private static void handleMarketHubClosed(Exception error) {
		String message = error == null ? "ProjectX market hub closed." : "ProjectX market hub closed: " + safeMessage(error.getMessage());
		boolean shouldReconnect = false;
		synchronized (ProjectXRealtimeManager.class) {
			if (shutdownRequested || suppressClosedCallbacks) {
				return;
			}
			runtime.marketHubConnected = false;
			runtime.marketHubStatus = "DISCONNECTED";
			runtime.marketHubMessage = message;
			if (runtime.running) {
				runtime.dataMode = "RECONNECTING";
				runtime.lastMessage = message + " Reconnect will renew the token and resubscribe.";
				shouldReconnect = true;
			} else {
				runtime.dataMode = "DISCONNECTED";
				runtime.lastMessage = message;
			}
		}
		if (shouldReconnect) {
			scheduleReconnect(message);
		}
	}

	private static void scheduleReconnect(String reason) {
		int attempt;
		long delayMs;
		synchronized (ProjectXRealtimeManager.class) {
			if (shutdownRequested || !runtime.running || reconnectScheduled) {
				return;
			}
			reconnectAttempt++;
			attempt = reconnectAttempt;
			delayMs = reconnectDelayMs(attempt);
			reconnectScheduled = true;
			runtime.dataMode = "RECONNECTING";
			runtime.lastMessage = "ProjectX realtime reconnect scheduled in " + (delayMs / 1000L) + "s. " + cleanOrDefault(reason, "");
		}
		REALTIME_RECONNECT_EXECUTOR.schedule(() -> reconnectRealtime(attempt, reason), delayMs, TimeUnit.MILLISECONDS);
	}

	private static long reconnectDelayMs(int attempt) {
		int index = Math.max(0, Math.min(RECONNECT_DELAYS_MS.length - 1, attempt - 1));
		return RECONNECT_DELAYS_MS[index];
	}

	private static void reconnectRealtime(int attempt, String reason) {
		String symbols;
		boolean includeDepth;
		synchronized (ProjectXRealtimeManager.class) {
			if (shutdownRequested || !runtime.running) {
				reconnectScheduled = false;
				return;
			}
			symbols = cleanOrDefault(runtime.symbols, "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL");
			includeDepth = runtime.includeDepth;
			runtime.dataMode = "RECONNECTING";
			runtime.lastMessage = "ProjectX realtime reconnect attempt " + attempt + " started. " + cleanOrDefault(reason, "");
		}

		RealtimeHubSession hubs = null;
		try {
			FuturesConnectionManager.TopstepxRealtimeConfig config = FuturesConnectionManager.createTopstepxRealtimeConfig();
			List<FuturesConnectionManager.TopstepxContractInfo> contracts = FuturesConnectionManager.resolveTopstepxRealtimeContracts(config, symbols);
			if (contracts.isEmpty()) {
				throw new IllegalStateException("No ProjectX contracts resolved for realtime subscriptions.");
			}
			RealtimeRuntime contractState = runtimeForContracts(config, contracts, includeDepth);
			synchronized (ProjectXRealtimeManager.class) {
				if (shutdownRequested || !runtime.running) {
					reconnectScheduled = false;
					return;
				}
				runtime.accountId = contractState.accountId;
				runtime.symbols = contractState.symbols;
				runtime.includeDepth = contractState.includeDepth;
				runtime.contractSymbols.clear();
				runtime.contractSymbols.putAll(contractState.contractSymbols);
				runtime.subscribedContracts.clear();
				runtime.subscribedContracts.addAll(contractState.subscribedContracts);
			}

			hubs = connectRealtimeHubs(config, contracts, includeDepth);
			HubConnection oldUser;
			HubConnection oldMarket;
			boolean closeNewSession = false;
			synchronized (ProjectXRealtimeManager.class) {
				if (shutdownRequested || !runtime.running) {
					reconnectScheduled = false;
					closeNewSession = true;
					oldUser = null;
					oldMarket = null;
				} else {
					oldUser = userConnection;
					oldMarket = marketConnection;
					userConnection = hubs.user;
					marketConnection = hubs.market;
					runtime.running = true;
					runtime.userHubConnected = hubs.userHubConnected;
					runtime.marketHubConnected = hubs.marketHubConnected;
					runtime.userHubStatus = hubs.userHubConnected ? "CONNECTED" : "UNAVAILABLE";
					runtime.marketHubStatus = hubs.marketHubConnected ? "CONNECTED" : "UNAVAILABLE";
					runtime.userHubMessage = hubs.userHubMessage;
					runtime.marketHubMessage = hubs.marketHubMessage;
					runtime.dataMode = hubs.userHubConnected ? "PROJECTX_SIGNALR" : "PROJECTX_SIGNALR_MARKET_ONLY";
					runtime.lastMessage = "ProjectX realtime reconnected, token renewed, and subscriptions renewed.";
					reconnectScheduled = false;
					reconnectAttempt = 0;
					suppressClosedCallbacks = true;
				}
			}
			if (closeNewSession) {
				closeHubSessionQuietly(hubs);
				return;
			}
			try {
				stopConnectionQuietly(oldMarket);
				stopConnectionQuietly(oldUser);
			} finally {
				synchronized (ProjectXRealtimeManager.class) {
					suppressClosedCallbacks = false;
				}
			}
		} catch (Exception e) {
			synchronized (ProjectXRealtimeManager.class) {
				suppressClosedCallbacks = true;
			}
			closeHubSessionQuietly(hubs);
			synchronized (ProjectXRealtimeManager.class) {
				suppressClosedCallbacks = false;
				reconnectScheduled = false;
				if (shutdownRequested || !runtime.running) {
					return;
				}
				runtime.userHubConnected = false;
				runtime.marketHubConnected = false;
				runtime.userHubStatus = "RECONNECTING";
				runtime.marketHubStatus = "RECONNECTING";
				runtime.dataMode = "RECONNECTING";
				runtime.lastMessage = "ProjectX realtime reconnect attempt " + attempt + " failed: " + safeMessage(e.getMessage());
			}
			scheduleReconnect("Retrying after reconnect failure: " + safeMessage(e.getMessage()));
		}
	}

	private static RealtimeRuntime runtimeForContracts(
		FuturesConnectionManager.TopstepxRealtimeConfig config,
		List<FuturesConnectionManager.TopstepxContractInfo> contracts,
		boolean includeDepth
	) {
		RealtimeRuntime next = new RealtimeRuntime();
		next.accountId = config.accountId;
		next.symbols = joinContractSymbols(contracts);
		next.includeDepth = includeDepth;
		for (int index = 0; index < contracts.size(); index++) {
			FuturesConnectionManager.TopstepxContractInfo contract = contracts.get(index);
			next.contractSymbols.put(contract.contractId, contract.symbol);
			next.subscribedContracts.add(contract.contractId);
		}
		return next;
	}

	private static void closeHubSessionQuietly(RealtimeHubSession hubs) {
		if (hubs == null) {
			return;
		}
		stopConnectionQuietly(hubs.market);
		stopConnectionQuietly(hubs.user);
	}

	private static void subscribeUserHub(HubConnection connection, String accountId) {
		int account = Integer.parseInt(accountId);
		sendWhenConnected(connection, "user", "SubscribeAccounts");
		sendWhenConnected(connection, "user", "SubscribeOrders", account);
		sendWhenConnected(connection, "user", "SubscribePositions", account);
		sendWhenConnected(connection, "user", "SubscribeTrades", account);
	}

	private static void subscribeMarketHub(
		HubConnection connection,
		List<FuturesConnectionManager.TopstepxContractInfo> contracts,
		boolean includeDepth
	) {
		for (int index = 0; index < contracts.size(); index++) {
			String contractId = contracts.get(index).contractId;
			sendWhenConnected(connection, "market", "SubscribeContractQuotes", contractId);
			sendWhenConnected(connection, "market", "SubscribeContractTrades", contractId);
			if (includeDepth) {
				sendWhenConnected(connection, "market", "SubscribeContractMarketDepth", contractId);
			}
		}
	}

	private static void startHubWithRetry(HubConnection connection, String hubName) {
		RuntimeException lastError = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try {
				connection.start().blockingAwait();
				waitForConnected(connection, hubName);
				return;
			} catch (RuntimeException e) {
				lastError = e;
				sleepQuietly(350L * attempt);
			}
		}
		throw new IllegalStateException("ProjectX " + hubName + " hub did not become active: " + safeMessage(lastError == null ? "" : lastError.getMessage()));
	}

	private static void sendWhenConnected(HubConnection connection, String hubName, String method, Object... args) {
		RuntimeException lastError = null;
		for (int attempt = 1; attempt <= 4; attempt++) {
			try {
				waitForConnected(connection, hubName);
				connection.send(method, args);
				return;
			} catch (RuntimeException e) {
				lastError = e;
				if (!isTransientInactiveConnection(e)) {
					throw e;
				}
				sleepQuietly(250L * attempt);
			}
		}
		throw new IllegalStateException("ProjectX " + hubName + " hub was not active for " + method + ": " + safeMessage(lastError == null ? "" : lastError.getMessage()));
	}

	private static void waitForConnected(HubConnection connection, String hubName) {
		for (int attempt = 0; attempt < 30; attempt++) {
			if (connection.getConnectionState() == HubConnectionState.CONNECTED) {
				return;
			}
			sleepQuietly(100L);
		}
		throw new IllegalStateException("ProjectX " + hubName + " hub connection state is " + connection.getConnectionState() + ".");
	}

	private static boolean isTransientInactiveConnection(RuntimeException e) {
		String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.US);
		return message.contains("connection is not active")
			|| message.contains("not be called if the connection is not active")
			|| message.contains("disconnected")
			|| message.contains("connecting");
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void stopConnectionsQuietly() {
		HubConnection user;
		HubConnection market;
		synchronized (ProjectXRealtimeManager.class) {
			user = userConnection;
			market = marketConnection;
			userConnection = null;
			marketConnection = null;
		}
		try {
			stopConnectionQuietly(market);
		} catch (Exception ignored) {
		}
		try {
			stopConnectionQuietly(user);
		} catch (Exception ignored) {
		}
	}

	private static void stopConnectionQuietly(HubConnection connection) {
		if (connection == null) {
			return;
		}
		try {
			connection.stop().blockingAwait();
		} catch (Exception ignored) {
		}
		try {
			connection.close();
		} catch (Exception ignored) {
		}
	}

	private static void recordRealtimeEvent(
		String hub,
		String eventType,
		String accountId,
		String contractId,
		String symbol,
		String payloadJson
	) {
		initializeStore();
		String receivedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMAT);
		String resolvedSymbol = symbol;
		if (isBlank(resolvedSymbol) && !isBlank(contractId)) {
			resolvedSymbol = symbolForContract(contractId);
		}
		if (isBlank(resolvedSymbol)) {
			resolvedSymbol = symbolFromRealtimePayload(payloadJson, contractId);
		}
		boolean persistEvent = shouldPersistRealtimeEvent(hub, eventType, resolvedSymbol);
		if (persistEvent) {
			String sql = "INSERT INTO FuturesLiveRealtimeEvents (hub, eventType, accountId, contractId, symbol, payloadJson, receivedAt) VALUES (?, ?, ?, ?, ?, ?, ?)";
			try (Connection conn = openRealtimeConnection();
				 PreparedStatement pstmt = conn.prepareStatement(sql)) {
				pstmt.setString(1, cleanOrDefault(hub, ""));
				pstmt.setString(2, cleanOrDefault(eventType, ""));
				pstmt.setString(3, cleanOrDefault(accountId, ""));
				pstmt.setString(4, cleanOrDefault(contractId, ""));
				pstmt.setString(5, cleanOrDefault(resolvedSymbol, ""));
				pstmt.setString(6, isBlank(payloadJson) ? "{}" : payloadJson);
				pstmt.setString(7, receivedAt);
				pstmt.executeUpdate();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		synchronized (ProjectXRealtimeManager.class) {
			if ("market".equalsIgnoreCase(hub)) {
				runtime.marketHubConnected = true;
				runtime.marketHubStatus = "CONNECTED";
				runtime.marketHubMessage = "ProjectX market event received.";
			} else if ("user".equalsIgnoreCase(hub)) {
				runtime.userHubConnected = true;
				runtime.userHubStatus = "CONNECTED";
				runtime.userHubMessage = "ProjectX user event received.";
			}
			runtime.eventCount++;
			runtime.lastEventAt = receivedAt;
			runtime.lastMessage = cleanOrDefault(eventType, "Realtime event") + " received.";
		}
		LiveRuntimeState.recordRealtimeEvent(hub, eventType, accountId, contractId, resolvedSymbol, payloadJson, receivedAt);
		FuturesMarketDataStore.recordRealtimeEvent(hub, eventType, resolvedSymbol, payloadJson, receivedAt);
	}

	private static boolean shouldPersistRealtimeEvent(String hub, String eventType, String symbol) {
		if (!"market".equalsIgnoreCase(cleanOrDefault(hub, ""))) {
			return true;
		}
		String normalizedSymbol = normalizeRealtimeSymbol(symbol);
		if (isBlank(normalizedSymbol)) {
			return true;
		}
		String normalizedType = cleanOrDefault(eventType, "RealtimeEvent");
		long interval = "GatewayQuote".equalsIgnoreCase(normalizedType) ? MARKET_QUOTE_PERSIST_INTERVAL_MS : MARKET_TRADE_PERSIST_INTERVAL_MS;
		long now = System.currentTimeMillis();
		String key = normalizedSymbol + "|" + normalizedType.toUpperCase(Locale.US);
		synchronized (ProjectXRealtimeManager.class) {
			Long previous = lastPersistedMarketEvents.get(key);
			if (previous != null && now - previous.longValue() < interval) {
				return false;
			}
			lastPersistedMarketEvents.put(key, now);
			if (lastPersistedMarketEvents.size() > 200) {
				String firstKey = lastPersistedMarketEvents.keySet().iterator().next();
				lastPersistedMarketEvents.remove(firstKey);
			}
			return true;
		}
	}

	private static String symbolForContract(String contractId) {
		synchronized (ProjectXRealtimeManager.class) {
			return cleanOrDefault(runtime.contractSymbols.get(contractId), "");
		}
	}

	private static String symbolFromRealtimePayload(String payloadJson, String contractId) {
		String payloadSymbol = firstNonBlank(
			jsonText(payloadJson, "symbolName"),
			jsonText(payloadJson, "symbol"),
			jsonText(payloadJson, "contract"),
			contractId
		);
		return normalizeRealtimeSymbol(payloadSymbol);
	}

	private static String normalizeRealtimeSymbol(String value) {
		String normalized = cleanOrDefault(value, "").toUpperCase(Locale.US).replace("/", "");
		if (isBlank(normalized)) {
			return "";
		}
		if (normalized.startsWith("CON.F.US.")) {
			String[] parts = normalized.split("\\.");
			if (parts.length >= 4) {
				normalized = parts[3];
			}
		} else if (normalized.startsWith("F.US.")) {
			String[] parts = normalized.split("\\.");
			if (parts.length >= 3) {
				normalized = parts[2];
			}
		}
		int dotIndex = normalized.indexOf('.');
		if (dotIndex > 0) {
			normalized = normalized.substring(0, dotIndex);
		}
		if ("EP".equals(normalized)) {
			return "ES";
		}
		if ("ENQ".equals(normalized)) {
			return "NQ";
		}
		if ("GCE".equals(normalized)) {
			return "GC";
		}
		return normalized;
	}

	private static RealtimeRuntime copyRuntime(RealtimeRuntime source) {
		RealtimeRuntime copy = new RealtimeRuntime();
		copy.running = source.running;
		copy.dataMode = source.dataMode;
		copy.accountId = source.accountId;
		copy.symbols = source.symbols;
		copy.startedAt = source.startedAt;
		copy.lastEventAt = source.lastEventAt;
		copy.lastMessage = source.lastMessage;
		copy.includeDepth = source.includeDepth;
		copy.userHubConnected = source.userHubConnected;
		copy.marketHubConnected = source.marketHubConnected;
		copy.userHubStatus = source.userHubStatus;
		copy.marketHubStatus = source.marketHubStatus;
		copy.userHubMessage = source.userHubMessage;
		copy.marketHubMessage = source.marketHubMessage;
		copy.eventCount = source.eventCount;
		copy.contractSymbols.putAll(source.contractSymbols);
		copy.subscribedContracts.addAll(source.subscribedContracts);
		return copy;
	}

	private static List<String> normalizeSymbols(String symbols) {
		List<String> values = new ArrayList<String>();
		String source = cleanOrDefault(symbols, "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL");
		String[] parts = source.split(",");
		for (int index = 0; index < parts.length; index++) {
			String symbol = parts[index] == null ? "" : parts[index].trim().toUpperCase(Locale.US);
			if (!isBlank(symbol) && !values.contains(symbol)) {
				values.add(symbol);
			}
		}
		if (values.isEmpty()) {
			values.add("MNQ");
		}
		return values;
	}

	private static String joinContractSymbols(List<FuturesConnectionManager.TopstepxContractInfo> contracts) {
		StringBuilder value = new StringBuilder();
		for (int index = 0; index < contracts.size(); index++) {
			if (index > 0) {
				value.append(",");
			}
			value.append(contracts.get(index).symbol);
		}
		return value.toString();
	}

	private static String accountIdFromPayload(Map data) {
		String accountId = stringValue(data.get("accountId"));
		if (!isBlank(accountId)) {
			return accountId;
		}
		return stringValue(data.get("id"));
	}

	private static String stripAccessToken(String url) {
		String cleaned = cleanOrDefault(url, "");
		int tokenIndex = cleaned.indexOf("?access_token=");
		if (tokenIndex >= 0) {
			return cleaned.substring(0, tokenIndex);
		}
		return cleaned;
	}

	private static String stringListJson(List<String> values) {
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < values.size(); index++) {
			if (index > 0) {
				json.append(",");
			}
			json.append(jsonString(values.get(index)));
		}
		json.append("]");
		return json.toString();
	}

	private static String jsonValue(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return String.valueOf(value);
		}
		if (value instanceof Map) {
			StringBuilder json = new StringBuilder("{");
			Map map = (Map) value;
			int index = 0;
			for (Object key : map.keySet()) {
				if (index > 0) {
					json.append(",");
				}
				json.append(jsonString(String.valueOf(key))).append(":").append(jsonValue(map.get(key)));
				index++;
			}
			json.append("}");
			return json.toString();
		}
		if (value instanceof Iterable) {
			StringBuilder json = new StringBuilder("[");
			int index = 0;
			for (Object item : (Iterable) value) {
				if (index > 0) {
					json.append(",");
				}
				json.append(jsonValue(item));
				index++;
			}
			json.append("]");
			return json.toString();
		}
		return jsonString(String.valueOf(value));
	}

	private static String stringValue(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Number) {
			double number = ((Number) value).doubleValue();
			if (Math.abs(number - Math.rint(number)) < 0.000001) {
				return String.valueOf((long) Math.rint(number));
			}
		}
		return String.valueOf(value);
	}

	private static String cleanOrDefault(String value, String defaultValue) {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		return value.trim();
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
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

	private static String jsonText(String json, String key) {
		if (json == null || key == null || key.trim().isEmpty()) {
			return "";
		}
		String needle = "\"" + key + "\":";
		int start = json.indexOf(needle);
		if (start < 0) {
			return "";
		}
		int index = start + needle.length();
		while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
			index++;
		}
		if (index >= json.length() || json.charAt(index) != '"') {
			return "";
		}
		StringBuilder value = new StringBuilder();
		boolean escaped = false;
		for (int cursor = index + 1; cursor < json.length(); cursor++) {
			char ch = json.charAt(cursor);
			if (escaped) {
				value.append(ch);
				escaped = false;
			} else if (ch == '\\') {
				escaped = true;
			} else if (ch == '"') {
				return value.toString();
			} else {
				value.append(ch);
			}
		}
		return "";
	}

	private static String safeMessage(String message) {
		if (message == null || message.trim().isEmpty()) {
			return "unknown error";
		}
		return message.replace('\n', ' ').replace('\r', ' ').trim();
	}

	private static String jsonString(String value) {
		if (value == null) {
			return "\"\"";
		}
		return "\"" + value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			+ "\"";
	}
}
