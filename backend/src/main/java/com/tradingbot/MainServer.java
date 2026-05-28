package com.tradingbot;

import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.Javalin;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.*;

public class MainServer {
    static final String PRIMARY_ACCOUNT_EMAIL = defaultAccountEmail();
    private static final String APP_VERSION = System.getProperty("tradingbot.version", "local-dev");
    private static final String BUILD_ID = System.getProperty("tradingbot.build", "unversioned");
    private static final boolean REQUIRE_APP_AUTH = configuredRequireAppAuth();
    private static final long STARTED_AT_MS = System.currentTimeMillis();
    private static final DateTimeFormatter TRADE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern ORB_RANGE_PATTERN = Pattern.compile("(?i)(?:\\d+m\\s+)?range\\s+high\\s+([0-9]+(?:\\.[0-9]+)?),\\s*low\\s+([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern IFVG_RANGE_PATTERN = Pattern.compile("(?i)flipped\\s+gap\\s+([0-9]+(?:\\.[0-9]+)?)\\s+to\\s+([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern VWAP_LEVEL_PATTERN = Pattern.compile("(?i)VWAP\\s+([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern PREVIOUS_CLOSE_PATTERN = Pattern.compile("(?i)previous\\s+close\\s+([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern OPENING_GAP_PATTERN = Pattern.compile("(?i)opening\\s+gap\\s+(-?[0-9]+(?:\\.[0-9]+)?)%");
    private static final Pattern BIAS_PATTERN = Pattern.compile("(?i)bias\\s+([a-z]+)\\s+from\\s+([^\\.]+)");
    private static final Pattern ORB_WINDOW_PATTERN = Pattern.compile("(?i)(\\d+)m\\s+range");
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_FAILURE_WINDOW_MS = 15L * 60L * 1000L;
    private static final long LOGIN_LOCKOUT_MS = 15L * 60L * 1000L;
    private static final String DEFAULT_PORTFOLIO_SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";
    private static final Map<String, LoginAttempt> LOGIN_ATTEMPTS = new ConcurrentHashMap<String, LoginAttempt>();

    public static void main(String[] args) {
        DatabaseManager.initializeDatabase();
        FuturesManager.initializeStore();
        FuturesConnectionManager.initializeStore();
        ProjectXRealtimeManager.initializeStore();
        
        AccountManager accountManager = new AccountManager();
        LiveBotManager liveBotManager = new LiveBotManager(accountManager);
        
        String bindHost = configuredBindHost();
        int port = parseIntOrDefault(System.getProperty("tradingbot.port", System.getenv("TRADINGBOT_PORT")), 7070);

        Javalin app = Javalin.create(config -> {
            config.enableCorsForOrigin(configuredCorsOrigins());
        }).start(bindHost, port);

        app.before("/api/*", ctx -> authorizeApiRequest(ctx, accountManager));
        app.error(404, ctx -> {
            if (ctx.path().startsWith("/api/")) {
                ctx.contentType("application/json").result("{"
                    + "\"success\":false,"
                    + "\"errorCode\":\"API_ROUTE_NOT_FOUND\","
                    + "\"message\":" + jsonString("No API route matched " + ctx.method() + " " + ctx.path()) + ","
                    + "\"method\":" + jsonString(ctx.method()) + ","
                    + "\"path\":" + jsonString(ctx.path())
                    + "}");
            }
        });
        app.exception(Exception.class, (error, ctx) -> {
            if (ctx.path().startsWith("/api/")) {
                int status = 500;
                String errorCode = "API_INTERNAL_ERROR";
                if (error instanceof UnauthorizedResponse) {
                    status = 401;
                    errorCode = "API_UNAUTHORIZED";
                } else if (error instanceof ForbiddenResponse) {
                    status = 403;
                    errorCode = "API_FORBIDDEN";
                }
                ctx.status(status).contentType("application/json").result("{"
                    + "\"success\":false,"
                    + "\"errorCode\":" + jsonString(errorCode) + ","
                    + "\"message\":" + jsonString(safeMessage(error.getMessage(), "Internal API error."))
                    + "}");
            } else {
                ctx.status(500).result(safeMessage(error.getMessage(), "Internal server error."));
            }
        });
        
        System.out.println("Backend Server is LIVE on http://" + bindHost + ":" + port);

        app.get("/api/system/version", ctx -> {
            ctx.contentType("application/json").result("{"
                + "\"app\":\"trading_bot\","
                + "\"version\":" + jsonString(APP_VERSION) + ","
                + "\"build\":" + jsonString(BUILD_ID)
                + "}");
        });

        app.get("/api/system/health", ctx -> {
            ctx.contentType("application/json").result("{"
                + "\"ok\":true,"
                + "\"uptimeSeconds\":" + Math.max(0L, (System.currentTimeMillis() - STARTED_AT_MS) / 1000L) + ","
                + "\"version\":" + jsonString(APP_VERSION)
                + "}");
        });

        app.get("/api/system/backend-update", ctx -> {
            ctx.contentType("application/json").result(backendUpdateStatusJson());
        });

        app.post("/api/system/backend-update", ctx -> {
            triggerBackendUpdate(ctx);
        });
        
        app.post("/api/login", ctx -> {
            String email = requestParam(ctx, "email");
            String password = requestParam(ctx, "password");
            String loginKey = loginThrottleKey(ctx, email);

            if (isLoginLocked(loginKey)) {
                ctx.status(429).result("Too many failed login attempts. Try again later.");
                return;
            }

            AccountManager.AccountSession session = accountManager.createSession(email, password);
            
            if(session != null) {
                clearLoginFailures(loginKey);
                ctx.contentType("application/json").result(sessionJson(session));
            } else {
                recordFailedLogin(loginKey);
                ctx.status(401).result("Invalid credentials.");
            }
        });

        app.get("/api/session", ctx -> {
            ctx.contentType("application/json").result("{"
                + "\"authenticated\":true,"
                + "\"email\":" + jsonString(authenticatedEmail(ctx)) + ","
                + "\"role\":" + jsonString(authenticatedRole(ctx)) + ","
                + "\"expiresAt\":" + jsonString(authenticatedExpiresAt(ctx))
                + "}");
        });

        app.post("/api/logout", ctx -> {
            accountManager.revokeSession(extractBearerToken(ctx));
            ctx.status(204);
        });

        app.post("/api/account/register", ctx -> {
            ctx.status(403).result("Account creation is disabled.");
        });
        
        app.get("/api/balance", ctx -> {
            String email = resolveAccountEmail(ctx, ctx.queryParam("email"));
            String apiKey = accountManager.getBrokerApiKey(email);
            String secretKey = accountManager.getBrokerSecretKey(email);

            if (isBlank(apiKey) || isBlank(secretKey)) {
                ctx.status(400).contentType("application/json").result("{\"error\":\"Broker keys not configured.\"}");
                return;
            }

            ctx.result(new AlpacaManager(apiKey, secretKey).getAccountInfo());
        });
        
        app.post("/api/trade", ctx -> {
            String email = resolveAccountEmail(ctx, ctx.queryParam("email"));
            String symbol = ctx.queryParam("symbol");
            String apiKey = accountManager.getBrokerApiKey(email);
            String secretKey = accountManager.getBrokerSecretKey(email);

            if (isBlank(apiKey) || isBlank(secretKey)) {
                ctx.status(400).result("Broker keys not configured.");
                return;
            }

            String result = new AlpacaManager(apiKey, secretKey).submitOrder(AlpacaManager.normalizeSymbol(symbol), 1, "buy");
            System.out.println("TRADE EXECUTED: " + result); 
            ctx.result(result);
        });

        app.get("/api/live-bot/status", ctx -> {
            String email = resolveAccountEmail(ctx, ctx.queryParam("email"));

            ctx.contentType("application/json").result(liveBotManager.statusToJson(liveBotManager.getStatus(email)));
        });

        app.post("/api/live-bot/start", ctx -> {
            String email = resolveAccountEmail(ctx, ctx.queryParam("email"));
            String symbol = ctx.queryParam("symbol");
            double perTradeBuyingPower = parseDoubleOrDefault(ctx.queryParam("perTradeBuyingPower"), 0.0);
            double takeProfit = parseDoubleOrDefault(ctx.queryParam("takeProfit"), 0.0);
            double lossLimit = parseDoubleOrDefault(ctx.queryParam("lossLimit"), 0.0);

            String apiKey = accountManager.getBrokerApiKey(email);
            String secretKey = accountManager.getBrokerSecretKey(email);
            if (isBlank(apiKey) || isBlank(secretKey)) {
                ctx.status(400).result("Broker keys not configured.");
                return;
            }

            if (!StrategyManager.loadStrategySettings().hasEnabledStrategies()) {
                ctx.status(400)
                    .contentType("application/json")
                    .result("{\"message\":\"Enable at least one strategy before starting the live bot.\"}");
                return;
            }

            LiveBotManager.LiveBotStatus status = liveBotManager.startBot(
                email,
                symbol,
                perTradeBuyingPower,
                takeProfit,
                lossLimit
            );

            if (!status.success) {
                ctx.status(500);
            }

            ctx.contentType("application/json").result(liveBotManager.statusToJson(status));
        });

        app.post("/api/live-bot/stop", ctx -> {
            String email = resolveAccountEmail(ctx, ctx.queryParam("email"));
            boolean force = parseBooleanOrDefault(ctx.queryParam("force"), false);

            LiveBotManager.LiveBotStatus status = liveBotManager.stopBot(email, force);
            if (status.requiresConfirmation) {
                ctx.status(409);
            } else if (!status.success) {
                ctx.status(500);
            }

            ctx.contentType("application/json").result(liveBotManager.statusToJson(status));
        });

        app.post("/api/account/change-password", ctx -> {
            String email = resolveAccountEmail(ctx, requestParam(ctx, "email"));
            String currentPassword = requestParam(ctx, "currentPassword");
            String newPassword = requestParam(ctx, "newPassword");

            if (isBlank(currentPassword) || isBlank(newPassword)) {
                ctx.status(400).result("Missing password fields.");
                return;
            }

            boolean passwordChanged = accountManager.changePassword(email, currentPassword, newPassword);

            if (passwordChanged) {
                ctx.status(204);
            } else {
                ctx.status(400).result("Password update failed.");
            }
        });

        app.post("/api/account/details", ctx -> {
            String currentEmail = resolveAccountEmail(ctx, requestParam(ctx, "currentEmail"));
            String name = requestParam(ctx, "name");
            String newEmail = resolveAccountEmail(ctx, requestParam(ctx, "email"));
            String phoneNumber = requestParam(ctx, "phoneNumber");
            String address = requestParam(ctx, "address");

            if (isBlank(name) || isBlank(phoneNumber) || isBlank(address)) {
                ctx.status(400).result("Missing account detail fields.");
                return;
            }

            boolean updated = accountManager.updateAccountDetails(
                currentEmail.trim(),
                name.trim(),
                newEmail.trim(),
                phoneNumber.trim(),
                address.trim()
            );

            if (updated) {
                ctx.status(204);
            } else {
                ctx.status(400).result("Failed to update account details.");
            }
        });

        app.get("/api/settings/account", ctx -> {
            String email = resolveAccountEmail(ctx, ctx.queryParam("email"));

            String accountName = accountManager.getAccountName(email);
            String phoneNumber = accountManager.getPhoneNumber(email);
            String address = accountManager.getAddress(email);

            ctx.contentType("application/json").result("{"
                + "\"name\":" + jsonString(accountName) + ","
                + "\"email\":" + jsonString(email) + ","
                + "\"phoneNumber\":" + jsonString(phoneNumber) + ","
                + "\"address\":" + jsonString(address)
                + "}");
        });

        app.get("/api/settings/broker", ctx -> {
            String email = resolveAccountEmail(ctx, ctx.queryParam("email"));

            String apiKey = accountManager.getBrokerApiKey(email);
            String secretKey = accountManager.getBrokerSecretKey(email);
            String connectedAccountName = "Not connected";

            if (!isBlank(apiKey) && !isBlank(secretKey)) {
                connectedAccountName = new AlpacaManager(apiKey, secretKey).getConnectedAccountName();
            }

            ctx.contentType("application/json").result("{"
                + "\"broker\":" + jsonString(AlpacaManager.getBrokerName()) + ","
                + "\"baseUrl\":" + jsonString(AlpacaManager.getBaseUrl()) + ","
                + "\"connectedAccountName\":" + jsonString(connectedAccountName) + ","
                + "\"hasApiKey\":" + !isBlank(apiKey) + ","
                + "\"apiKeyPreview\":" + jsonString(maskSecret(apiKey)) + ","
                + "\"hasSecretKey\":" + !isBlank(secretKey) + ","
                + "\"secretKeyPreview\":" + jsonString(maskSecret(secretKey))
                + "}");
        });

        app.post("/api/settings/broker", ctx -> {
            String email = resolveAccountEmail(ctx, requestParam(ctx, "email"));
            String apiKey = requestParam(ctx, "apiKey");
            String secretKey = requestParam(ctx, "secretKey");

            String finalApiKey = isBlank(apiKey) ? accountManager.getBrokerApiKey(email) : apiKey.trim();
            String finalSecretKey = isBlank(secretKey) ? accountManager.getBrokerSecretKey(email) : secretKey.trim();

            if (isBlank(finalApiKey) || isBlank(finalSecretKey)) {
                ctx.status(400).result("Missing broker settings.");
                return;
            }

            boolean updated = accountManager.updateBrokerKeys(email, finalApiKey, finalSecretKey);

            if (updated) {
                ctx.status(204);
            } else {
                ctx.status(500).result("Failed to update broker settings.");
            }
        });

        app.get("/api/backtests/market-data", ctx -> {
            ctx.contentType("application/json").result(AlpacaManager.getMarketDataStatusJson());
        });

        app.get("/api/strategy", ctx -> {
            ctx.contentType("application/json").result(StrategyManager.getStrategySettingsJson());
        });

        app.post("/api/strategy", ctx -> {
            StrategyManager.StrategySettings settings = StrategyManager.loadStrategySettings();

            settings.orb.isEnabled = parseBooleanOrDefault(ctx.queryParam("orbEnabled"), settings.orb.isEnabled);
            settings.orb.trendTimeframe = valueOrDefault(ctx.queryParam("orbTrendTimeframe"), settings.orb.trendTimeframe);
            settings.orb.orbWindowMinutes = parseIntOrDefault(ctx.queryParam("orbWindowMinutes"), settings.orb.orbWindowMinutes);
            settings.orb.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("orbMaxTradesPerDay"), settings.orb.maxTradesPerDay);
            settings.orb.breakoutBufferPct = parseDoubleOrDefault(ctx.queryParam("orbBreakoutBufferPct"), settings.orb.breakoutBufferPct);
            settings.orb.reclaimWindowBars = parseIntOrDefault(ctx.queryParam("orbReclaimWindowBars"), settings.orb.reclaimWindowBars);
            settings.orb.entryBufferPct = parseDoubleOrDefault(ctx.queryParam("orbEntryBufferPct"), settings.orb.entryBufferPct);
            settings.orb.riskPerTradePct = parseDoubleOrDefault(ctx.queryParam("orbRiskPerTradePct"), settings.orb.riskPerTradePct);
            settings.orb.rewardToRiskRatio = parseDoubleOrDefault(ctx.queryParam("orbRewardToRiskRatio"), settings.orb.rewardToRiskRatio);
            settings.orb.stopBufferPct = parseDoubleOrDefault(ctx.queryParam("orbStopBufferPct"), settings.orb.stopBufferPct);
            settings.orb.requireTrendAlignment = parseBooleanOrDefault(
                ctx.queryParam("orbRequireTrendAlignment"),
                settings.orb.requireTrendAlignment
            );

            settings.ifvg.isEnabled = parseBooleanOrDefault(ctx.queryParam("ifvgEnabled"), settings.ifvg.isEnabled);
            settings.ifvg.trendTimeframe = valueOrDefault(ctx.queryParam("ifvgTrendTimeframe"), settings.ifvg.trendTimeframe);
            settings.ifvg.signalTimeframe = valueOrDefault(ctx.queryParam("ifvgSignalTimeframe"), settings.ifvg.signalTimeframe);
            settings.ifvg.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("ifvgMaxTradesPerDay"), settings.ifvg.maxTradesPerDay);
            settings.ifvg.minimumGapPct = parseDoubleOrDefault(ctx.queryParam("ifvgMinimumGapPct"), settings.ifvg.minimumGapPct);
            settings.ifvg.reclaimWindowBars = parseIntOrDefault(ctx.queryParam("ifvgReclaimWindowBars"), settings.ifvg.reclaimWindowBars);
            settings.ifvg.riskPerTradePct = parseDoubleOrDefault(ctx.queryParam("ifvgRiskPerTradePct"), settings.ifvg.riskPerTradePct);
            settings.ifvg.rewardToRiskRatio = parseDoubleOrDefault(ctx.queryParam("ifvgRewardToRiskRatio"), settings.ifvg.rewardToRiskRatio);
            settings.ifvg.entryBufferPct = parseDoubleOrDefault(ctx.queryParam("ifvgEntryBufferPct"), settings.ifvg.entryBufferPct);
            settings.ifvg.stopBufferPct = parseDoubleOrDefault(ctx.queryParam("ifvgStopBufferPct"), settings.ifvg.stopBufferPct);
            settings.ifvg.requireTrendAlignment = parseBooleanOrDefault(
                ctx.queryParam("ifvgRequireTrendAlignment"),
                settings.ifvg.requireTrendAlignment
            );

            settings.vwapPullback.isEnabled = parseBooleanOrDefault(ctx.queryParam("vwapEnabled"), settings.vwapPullback.isEnabled);
            settings.vwapPullback.trendTimeframe = valueOrDefault(ctx.queryParam("vwapTrendTimeframe"), settings.vwapPullback.trendTimeframe);
            settings.vwapPullback.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("vwapMaxTradesPerDay"), settings.vwapPullback.maxTradesPerDay);
            settings.vwapPullback.minimumGapPct = parseDoubleOrDefault(ctx.queryParam("vwapMinimumGapPct"), settings.vwapPullback.minimumGapPct);
            settings.vwapPullback.reclaimWindowBars = parseIntOrDefault(ctx.queryParam("vwapReclaimWindowBars"), settings.vwapPullback.reclaimWindowBars);
            settings.vwapPullback.riskPerTradePct = parseDoubleOrDefault(ctx.queryParam("vwapRiskPerTradePct"), settings.vwapPullback.riskPerTradePct);
            settings.vwapPullback.rewardToRiskRatio = parseDoubleOrDefault(ctx.queryParam("vwapRewardToRiskRatio"), settings.vwapPullback.rewardToRiskRatio);
            settings.vwapPullback.entryBufferPct = parseDoubleOrDefault(ctx.queryParam("vwapEntryBufferPct"), settings.vwapPullback.entryBufferPct);
            settings.vwapPullback.stopBufferPct = parseDoubleOrDefault(ctx.queryParam("vwapStopBufferPct"), settings.vwapPullback.stopBufferPct);
            settings.vwapPullback.requireTrendAlignment = parseBooleanOrDefault(
                ctx.queryParam("vwapRequireTrendAlignment"),
                settings.vwapPullback.requireTrendAlignment
            );

            settings.vwapMeanReversion.isEnabled = parseBooleanOrDefault(ctx.queryParam("vwapMeanReversionEnabled"), settings.vwapMeanReversion.isEnabled);
            settings.vwapMeanReversion.trendTimeframe = valueOrDefault(ctx.queryParam("vwapMeanReversionTrendTimeframe"), settings.vwapMeanReversion.trendTimeframe);
            settings.vwapMeanReversion.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("vwapMeanReversionMaxTradesPerDay"), settings.vwapMeanReversion.maxTradesPerDay);
            settings.vwapMeanReversion.minimumGapPct = parseDoubleOrDefault(ctx.queryParam("vwapMeanReversionMinimumGapPct"), settings.vwapMeanReversion.minimumGapPct);
            settings.vwapMeanReversion.reclaimWindowBars = parseIntOrDefault(ctx.queryParam("vwapMeanReversionReclaimWindowBars"), settings.vwapMeanReversion.reclaimWindowBars);
            settings.vwapMeanReversion.riskPerTradePct = parseDoubleOrDefault(ctx.queryParam("vwapMeanReversionRiskPerTradePct"), settings.vwapMeanReversion.riskPerTradePct);
            settings.vwapMeanReversion.rewardToRiskRatio = parseDoubleOrDefault(ctx.queryParam("vwapMeanReversionRewardToRiskRatio"), settings.vwapMeanReversion.rewardToRiskRatio);
            settings.vwapMeanReversion.entryBufferPct = parseDoubleOrDefault(ctx.queryParam("vwapMeanReversionEntryBufferPct"), settings.vwapMeanReversion.entryBufferPct);
            settings.vwapMeanReversion.stopBufferPct = parseDoubleOrDefault(ctx.queryParam("vwapMeanReversionStopBufferPct"), settings.vwapMeanReversion.stopBufferPct);
            settings.vwapMeanReversion.requireTrendAlignment = parseBooleanOrDefault(
                ctx.queryParam("vwapMeanReversionRequireTrendAlignment"),
                settings.vwapMeanReversion.requireTrendAlignment
            );

            settings.gapGo.isEnabled = parseBooleanOrDefault(ctx.queryParam("gapGoEnabled"), settings.gapGo.isEnabled);
            settings.gapGo.trendTimeframe = valueOrDefault(ctx.queryParam("gapGoTrendTimeframe"), settings.gapGo.trendTimeframe);
            settings.gapGo.orbWindowMinutes = parseIntOrDefault(ctx.queryParam("gapGoOrbWindowMinutes"), settings.gapGo.orbWindowMinutes);
            settings.gapGo.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("gapGoMaxTradesPerDay"), settings.gapGo.maxTradesPerDay);
            settings.gapGo.breakoutBufferPct = parseDoubleOrDefault(ctx.queryParam("gapGoBreakoutBufferPct"), settings.gapGo.breakoutBufferPct);
            settings.gapGo.minimumGapPct = parseDoubleOrDefault(ctx.queryParam("gapGoMinimumGapPct"), settings.gapGo.minimumGapPct);
            settings.gapGo.riskPerTradePct = parseDoubleOrDefault(ctx.queryParam("gapGoRiskPerTradePct"), settings.gapGo.riskPerTradePct);
            settings.gapGo.rewardToRiskRatio = parseDoubleOrDefault(ctx.queryParam("gapGoRewardToRiskRatio"), settings.gapGo.rewardToRiskRatio);
            settings.gapGo.stopBufferPct = parseDoubleOrDefault(ctx.queryParam("gapGoStopBufferPct"), settings.gapGo.stopBufferPct);
            settings.gapGo.requireTrendAlignment = parseBooleanOrDefault(
                ctx.queryParam("gapGoRequireTrendAlignment"),
                settings.gapGo.requireTrendAlignment
            );

            boolean saved = StrategyManager.saveStrategySettings(settings);

            if (saved) {
                ctx.contentType("application/json").result(StrategyManager.getStrategySettingsJson());
            } else {
                ctx.status(500).result("Failed to save strategy settings.");
            }
        });

        app.post("/api/backtests/market-data/refresh", ctx -> {
            String email = resolveAccountEmail(ctx, ctx.queryParam("email"));

            String apiKey = accountManager.getBrokerApiKey(email);
            String secretKey = accountManager.getBrokerSecretKey(email);

            if (isBlank(apiKey) || isBlank(secretKey)) {
                ctx.status(400).result("Broker keys not configured.");
                return;
            }

            boolean refreshed = new AlpacaManager(apiKey, secretKey).refreshHistoricalDataCache();

            if (refreshed) {
                ctx.contentType("application/json").result(AlpacaManager.getMarketDataStatusJson());
            } else {
                ctx.status(500).result("Failed to refresh market data.");
            }
        });

        app.post("/api/backtests/generate", ctx -> {
            String equity = ctx.queryParam("equity");
            String startDate = ctx.queryParam("startDate");
            String endDate = ctx.queryParam("endDate");
            double totalBuyingPower = parseDoubleOrDefault(ctx.queryParam("totalBuyingPower"), 25000.0);
            double perTradeBuyingPower = parseDoubleOrDefault(ctx.queryParam("perTradeBuyingPower"), totalBuyingPower);
            double takeProfit = parseDoubleOrDefault(ctx.queryParam("takeProfit"), 1000.0);
            double lossLimit = parseDoubleOrDefault(ctx.queryParam("lossLimit"), 500.0);

            if (!StrategyManager.loadStrategySettings().hasEnabledStrategies()) {
                ctx.status(400)
                    .contentType("application/json")
                    .result("{\"message\":\"Enable at least one strategy before running a backtest.\"}");
                return;
            }

            int backtestId = DatabaseManager.generateStrategyBacktest(
                equity,
                startDate,
                endDate,
                totalBuyingPower,
                perTradeBuyingPower,
                takeProfit,
                lossLimit
            );

            if (backtestId > 0) {
                ctx.status(201)
                    .contentType("application/json")
                    .result("{\"backtestId\":" + backtestId + "}");
            } else {
                ctx.status(500)
                    .contentType("application/json")
                    .result("{\"message\":\"Failed to generate backtest from market data.\"}");
            }
        });

        app.post("/api/backtests/clear", ctx -> {
            boolean cleared = DatabaseManager.clearBacktests();

            if (cleared) {
                ctx.status(204);
            } else {
                ctx.status(500).result("Failed to clear backtest history.");
            }
        });

        app.get("/api/backtests", ctx -> {
            StringBuilder json = new StringBuilder("[");
            try (Connection conn = DatabaseManager.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM Backtests ORDER BY backtestID DESC")) {
                while (rs.next()) {
                    if (json.length() > 1) json.append(",");
                    json.append("{")
                        .append("\"recordId\":").append(rs.getInt("backtestID")).append(",")
                        .append("\"name\":\"").append(rs.getString("backtestName")).append("\",")
                        .append("\"equity\":\"").append(rs.getString("symbols")).append("\",")
                        .append("\"start\":\"").append(rs.getString("startDate")).append("\",")
                        .append("\"end\":\"").append(rs.getString("endDate")).append("\",")
                        .append("\"startingCapital\":").append(rs.getDouble("startingCapital")).append(",")
                        .append("\"endingCapital\":").append(rs.getDouble("endingCapital")).append(",")
                        .append("\"totalProfit\":").append(rs.getDouble("totalProfit")).append(",")
                        .append("\"totalReturn\":").append(rs.getDouble("returnPct")).append(",")
                        .append("\"winRate\":").append(rs.getDouble("winRate")).append(",")
                        .append("\"trades\":").append(rs.getInt("numTrades")).append(",")
                        .append("\"profitFactor\":").append(rs.getDouble("profitFactor")).append(",")
                        .append("\"drawdown\":").append(rs.getDouble("maxDrawdownPct"))
                        .append("}");
                }
            }
            json.append("]");
            ctx.contentType("application/json").result(json.toString());
        });

        app.get("/api/backtests/{id}/trades", ctx -> {
            int backtestId;

            try {
                backtestId = Integer.parseInt(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid backtest ID.");
                return;
            }

            StringBuilder json = new StringBuilder("[");
            String sql = "SELECT * FROM Trades WHERE backtestID = ? ORDER BY tradeID ASC";

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, backtestId);

                try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    if (json.length() > 1) json.append(",");
                    json.append("{")
                        .append("\"id\":").append(rs.getInt("tradeID")).append(",")
                        .append("\"symbol\":").append(jsonString(rs.getString("symbol"))).append(",")
                        .append("\"strategyCode\":").append(jsonString(rs.getString("strategyCode"))).append(",")
                        .append("\"strategyName\":").append(jsonString(rs.getString("strategyName"))).append(",")
                        .append("\"time\":").append(jsonString(rs.getString("openedAt"))).append(",")
                        .append("\"closedAt\":").append(jsonString(rs.getString("closedAt"))).append(",")
                        .append("\"side\":").append(jsonString(rs.getString("side"))).append(",")
                        .append("\"qty\":").append(rs.getDouble("qty")).append(",")
                        .append("\"entry\":").append(rs.getDouble("entryPrice")).append(",")
                        .append("\"exit\":").append(rs.getDouble("exitPrice")).append(",")
                        .append("\"pnl\":").append(rs.getDouble("pnl")).append(",")
                        .append("\"tradeNotes\":").append(jsonString(rs.getString("tradeNotes")))
                        .append("}");
                }
                }
            }
            json.append("]");
            ctx.contentType("application/json").result(json.toString());
        });

        app.get("/api/backtests/{id}/trades/{tradeId}/chart", ctx -> {
            int backtestId;
            int tradeId;

            try {
                backtestId = Integer.parseInt(ctx.pathParam("id"));
                tradeId = Integer.parseInt(ctx.pathParam("tradeId"));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid backtest trade request.");
                return;
            }

            String timeframe = normalizeTradeChartTimeframe(ctx.queryParam("timeframe"));
            String sql = "SELECT * FROM Trades WHERE backtestID = ? AND tradeID = ? LIMIT 1";

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, backtestId);
                pstmt.setInt(2, tradeId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        ctx.status(404).result("Trade not found.");
                        return;
                    }

                    ctx.contentType("application/json").result(buildTradeChartJson(rs, timeframe));
                }
            }
        });

        app.get("/api/futures/instruments", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getInstrumentJson());
        });

        app.get("/api/futures/market-data", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getMarketDataStatusJson());
        });

	        app.get("/api/futures/strategy", ctx -> {
	            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
	            String preset = ctx.queryParam("preset");
	            String slot = preset == null ? valueOrDefault(ctx.queryParam("slot"), "BACKTEST") : FuturesManager.strategyPresetSlot(preset);
	            ctx.contentType("application/json").result(FuturesManager.getFuturesStrategySettingsJson(symbol, slot));
	        });

	        app.get("/api/futures/strategy-presets", ctx -> {
	            ctx.contentType("application/json").result(FuturesManager.getStrategyPresetsJson());
	        });

	        app.get("/api/futures/strategy-window-policy", ctx -> {
	            ctx.contentType("application/json").result(FuturesManager.getStrategyWindowPolicyJson());
	        });

	        app.post("/api/futures/strategy-presets", ctx -> {
	            String preset = valueOrDefault(ctx.queryParam("preset"), "");
	            String sourcePreset = valueOrDefault(ctx.queryParam("sourcePreset"), "94k");
	            String result = FuturesManager.createStrategyPreset(preset, sourcePreset);
	            if (result.contains("\"success\":false")) {
	                ctx.status(400);
	            }
	            ctx.contentType("application/json").result(result);
	        });

	        app.post("/api/futures/strategy", ctx -> {
	            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
	            String preset = ctx.queryParam("preset");
	            String slot = preset == null ? valueOrDefault(ctx.queryParam("slot"), "BACKTEST") : FuturesManager.strategyPresetSlot(preset);
	            if ("LIVE".equalsIgnoreCase(slot)) {
	                ctx.status(400).contentType("application/json").result("{\"message\":\"Live Strategy slot is legacy read-only. Save a named strategy preset instead.\"}");
	                return;
	            }
	            if (preset != null && "94k".equalsIgnoreCase(preset.trim())) {
	                ctx.status(400).contentType("application/json").result("{\"message\":\"94k is the frozen backup Strategy Config. Save strategy-improvement edits to wip.\"}");
	                return;
	            }
	            FuturesManager.FuturesStrategySettings settings = FuturesManager.loadFuturesStrategySettings(symbol, slot);
            settings.orb.enabled = parseBooleanOrDefault(ctx.queryParam("orbEnabled"), settings.orb.enabled);
            settings.orb.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("orbMaxTradesPerDay"), settings.orb.maxTradesPerDay);
            settings.openingMomentum.enabled = parseBooleanOrDefault(ctx.queryParam("openingMomentumEnabled"), settings.openingMomentum.enabled);
            settings.openingMomentum.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("openingMomentumMaxTradesPerDay"), settings.openingMomentum.maxTradesPerDay);
            settings.sweep.enabled = parseBooleanOrDefault(ctx.queryParam("sweepEnabled"), settings.sweep.enabled);
            settings.sweep.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("sweepMaxTradesPerDay"), settings.sweep.maxTradesPerDay);
            settings.vwapPullback.enabled = parseBooleanOrDefault(ctx.queryParam("vwapPullbackEnabled"), settings.vwapPullback.enabled);
            settings.vwapPullback.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("vwapPullbackMaxTradesPerDay"), settings.vwapPullback.maxTradesPerDay);
            settings.vwapMeanReversion.enabled = parseBooleanOrDefault(ctx.queryParam("vwapMeanReversionEnabled"), settings.vwapMeanReversion.enabled);
            settings.vwapMeanReversion.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("vwapMeanReversionMaxTradesPerDay"), settings.vwapMeanReversion.maxTradesPerDay);
            settings.fvg.enabled = parseBooleanOrDefault(ctx.queryParam("fvgEnabled"), settings.fvg.enabled);
            settings.fvg.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("fvgMaxTradesPerDay"), settings.fvg.maxTradesPerDay);
            settings.closeMomentum.enabled = parseBooleanOrDefault(ctx.queryParam("closeMomentumEnabled"), settings.closeMomentum.enabled);
            settings.closeMomentum.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("closeMomentumMaxTradesPerDay"), settings.closeMomentum.maxTradesPerDay);
            settings.afternoonContinuation.enabled = parseBooleanOrDefault(ctx.queryParam("afternoonContinuationEnabled"), settings.afternoonContinuation.enabled);
            settings.afternoonContinuation.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("afternoonContinuationMaxTradesPerDay"), settings.afternoonContinuation.maxTradesPerDay);
            settings.marketIntradayMomentum.enabled = parseBooleanOrDefault(ctx.queryParam("marketIntradayMomentumEnabled"), settings.marketIntradayMomentum.enabled);
            settings.marketIntradayMomentum.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("marketIntradayMomentumMaxTradesPerDay"), settings.marketIntradayMomentum.maxTradesPerDay);
            settings.keltnerScalp.enabled = parseBooleanOrDefault(ctx.queryParam("keltnerScalpEnabled"), settings.keltnerScalp.enabled);
            settings.keltnerScalp.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("keltnerScalpMaxTradesPerDay"), settings.keltnerScalp.maxTradesPerDay);
            settings.keltnerReversion.enabled = parseBooleanOrDefault(ctx.queryParam("keltnerReversionEnabled"), settings.keltnerReversion.enabled);
            settings.keltnerReversion.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("keltnerReversionMaxTradesPerDay"), settings.keltnerReversion.maxTradesPerDay);
            settings.microScalp.enabled = parseBooleanOrDefault(ctx.queryParam("microScalpEnabled"), settings.microScalp.enabled);
            settings.microScalp.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("microScalpMaxTradesPerDay"), settings.microScalp.maxTradesPerDay);
            settings.mclEiaContinuation.enabled = parseBooleanOrDefault(ctx.queryParam("mclEiaContinuationEnabled"), settings.mclEiaContinuation.enabled);
            settings.mclEiaContinuation.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("mclEiaContinuationMaxTradesPerDay"), settings.mclEiaContinuation.maxTradesPerDay);
            settings.mclCrudeSessionOpen.enabled = parseBooleanOrDefault(ctx.queryParam("mclCrudeSessionOpenEnabled"), settings.mclCrudeSessionOpen.enabled);
            settings.mclCrudeSessionOpen.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("mclCrudeSessionOpenMaxTradesPerDay"), settings.mclCrudeSessionOpen.maxTradesPerDay);
            settings.mymIndexConfirmation.enabled = parseBooleanOrDefault(ctx.queryParam("mymIndexConfirmationEnabled"), settings.mymIndexConfirmation.enabled);
            settings.mymIndexConfirmation.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("mymIndexConfirmationMaxTradesPerDay"), settings.mymIndexConfirmation.maxTradesPerDay);
            settings.mymOrbRetest.enabled = parseBooleanOrDefault(ctx.queryParam("mymOrbRetestEnabled"), settings.mymOrbRetest.enabled);
            settings.mymOrbRetest.maxTradesPerDay = parseIntOrDefault(ctx.queryParam("mymOrbRetestMaxTradesPerDay"), settings.mymOrbRetest.maxTradesPerDay);
            settings.enableEarlySweep = parseBooleanOrDefault(ctx.queryParam("enableEarlySweep"), settings.enableEarlySweep);
            settings.enableLateSweep = parseBooleanOrDefault(ctx.queryParam("enableLateSweep"), settings.enableLateSweep);
            settings.enableSweepSecondChance = parseBooleanOrDefault(ctx.queryParam("enableSweepSecondChance"), settings.enableSweepSecondChance);
            settings.enableOrbRetest = parseBooleanOrDefault(ctx.queryParam("enableOrbRetest"), settings.enableOrbRetest);
            settings.allowOrbRetestLongs = parseBooleanOrDefault(ctx.queryParam("allowOrbRetestLongs"), settings.allowOrbRetestLongs);
            settings.allowOrbRetestShorts = parseBooleanOrDefault(ctx.queryParam("allowOrbRetestShorts"), settings.allowOrbRetestShorts);
            settings.orbRetestStartMinutes = parseIntOrDefault(ctx.queryParam("orbRetestStartMinutes"), settings.orbRetestStartMinutes);
            settings.orbRetestEndMinutes = parseIntOrDefault(ctx.queryParam("orbRetestEndMinutes"), settings.orbRetestEndMinutes);
            settings.orbBreakoutEndMinute = parseIntOrDefault(ctx.queryParam("orbBreakoutEndMinute"), settings.orbBreakoutEndMinute);
            settings.orbShortConfirmationMinute = parseIntOrDefault(ctx.queryParam("orbShortConfirmationMinute"), settings.orbShortConfirmationMinute);
            settings.enableCompressedOrbBreakout = parseBooleanOrDefault(ctx.queryParam("enableCompressedOrbBreakout"), settings.enableCompressedOrbBreakout);
            settings.skipMidmorningOrbRetest = parseBooleanOrDefault(ctx.queryParam("skipMidmorningOrbRetest"), settings.skipMidmorningOrbRetest);
            settings.requireHigherTimeframeGuard = parseBooleanOrDefault(ctx.queryParam("requireHigherTimeframeGuard"), settings.requireHigherTimeframeGuard);
            settings.relaxPatternHardWindows = parseBooleanOrDefault(ctx.queryParam("relaxPatternHardWindows"), settings.relaxPatternHardWindows);
            settings.allowShorts = parseBooleanOrDefault(ctx.queryParam("allowShorts"), settings.allowShorts);
            settings.openingMomentumRangeMinutes = parseIntOrDefault(ctx.queryParam("openingMomentumRangeMinutes"), settings.openingMomentumRangeMinutes);
            settings.openingMomentumMaxHoldBars = parseIntOrDefault(ctx.queryParam("openingMomentumMaxHoldBars"), settings.openingMomentumMaxHoldBars);
            settings.openingMomentumVolumeRatio = parseDoubleOrDefault(ctx.queryParam("openingMomentumVolumeRatio"), settings.openingMomentumVolumeRatio);
            settings.openingMomentumRewardRisk = parseDoubleOrDefault(ctx.queryParam("openingMomentumRewardRisk"), settings.openingMomentumRewardRisk);
            settings.earlySweepReclaimTicks = parseDoubleOrDefault(ctx.queryParam("earlySweepReclaimTicks"), settings.earlySweepReclaimTicks);
            settings.lateSweepReclaimTicks = parseDoubleOrDefault(ctx.queryParam("lateSweepReclaimTicks"), settings.lateSweepReclaimTicks);
            settings.sweepCloseLocation = parseDoubleOrDefault(ctx.queryParam("sweepCloseLocation"), settings.sweepCloseLocation);
            settings.lateSweepCloseLocation = parseDoubleOrDefault(ctx.queryParam("lateSweepCloseLocation"), settings.lateSweepCloseLocation);
            settings.minBodyPct = parseDoubleOrDefault(ctx.queryParam("minBodyPct"), settings.minBodyPct);
            settings.vwapMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("vwapMinVolumeRatio"), settings.vwapMinVolumeRatio);
            settings.vwapMinTrendSlopeTicks = parseDoubleOrDefault(ctx.queryParam("vwapMinTrendSlopeTicks"), settings.vwapMinTrendSlopeTicks);
            settings.vwapMaxDistanceTicks = parseDoubleOrDefault(ctx.queryParam("vwapMaxDistanceTicks"), settings.vwapMaxDistanceTicks);
            settings.vwapMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("vwapMaxRiskTicks"), settings.vwapMaxRiskTicks);
            settings.vwapRequireHigherTimeframeGuard = parseBooleanOrDefault(ctx.queryParam("vwapRequireHigherTimeframeGuard"), settings.vwapRequireHigherTimeframeGuard);
            settings.fvgRequireCoreQuality = parseBooleanOrDefault(ctx.queryParam("fvgRequireCoreQuality"), settings.fvgRequireCoreQuality);
            settings.fvgRequireEmaStack = parseBooleanOrDefault(ctx.queryParam("fvgRequireEmaStack"), settings.fvgRequireEmaStack);
            settings.fvgRequireHigherTimeframeGuard = parseBooleanOrDefault(ctx.queryParam("fvgRequireHigherTimeframeGuard"), settings.fvgRequireHigherTimeframeGuard);
            settings.fvgMinImpulseBodyPct = parseDoubleOrDefault(ctx.queryParam("fvgMinImpulseBodyPct"), settings.fvgMinImpulseBodyPct);
            settings.fvgMinTrendSlopeTicks = parseDoubleOrDefault(ctx.queryParam("fvgMinTrendSlopeTicks"), settings.fvgMinTrendSlopeTicks);
            settings.fvgMaxVwapDistanceTicks = parseDoubleOrDefault(ctx.queryParam("fvgMaxVwapDistanceTicks"), settings.fvgMaxVwapDistanceTicks);
            settings.fvgMaxEntryExtensionTicks = parseDoubleOrDefault(ctx.queryParam("fvgMaxEntryExtensionTicks"), settings.fvgMaxEntryExtensionTicks);
            settings.meanReversionMinDistanceTicks = parseDoubleOrDefault(ctx.queryParam("meanReversionMinDistanceTicks"), settings.meanReversionMinDistanceTicks);
            settings.meanReversionOversoldRsi = parseDoubleOrDefault(ctx.queryParam("meanReversionOversoldRsi"), settings.meanReversionOversoldRsi);
            settings.meanReversionOverboughtRsi = parseDoubleOrDefault(ctx.queryParam("meanReversionOverboughtRsi"), settings.meanReversionOverboughtRsi);
            settings.minRewardRisk = parseDoubleOrDefault(ctx.queryParam("minRewardRisk"), settings.minRewardRisk);
            settings.allowCloseMomentumLongs = parseBooleanOrDefault(ctx.queryParam("allowCloseMomentumLongs"), settings.allowCloseMomentumLongs);
            settings.allowCloseMomentumShorts = parseBooleanOrDefault(ctx.queryParam("allowCloseMomentumShorts"), settings.allowCloseMomentumShorts);
            settings.closeMomentumMinMoveTicks = parseDoubleOrDefault(ctx.queryParam("closeMomentumMinMoveTicks"), settings.closeMomentumMinMoveTicks);
            settings.closeMomentumVolumeRatio = parseDoubleOrDefault(ctx.queryParam("closeMomentumVolumeRatio"), settings.closeMomentumVolumeRatio);
            settings.closeMomentumRewardRisk = parseDoubleOrDefault(ctx.queryParam("closeMomentumRewardRisk"), settings.closeMomentumRewardRisk);
            settings.orbCompressedMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("orbCompressedMaxRiskTicks"), settings.orbCompressedMaxRiskTicks);
            settings.orbRetestMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("orbRetestMaxRiskTicks"), settings.orbRetestMaxRiskTicks);
            settings.afternoonMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("afternoonMinVolumeRatio"), settings.afternoonMinVolumeRatio);
            settings.afternoonMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("afternoonMaxRiskTicks"), settings.afternoonMaxRiskTicks);
            settings.afternoonRewardRisk = parseDoubleOrDefault(ctx.queryParam("afternoonRewardRisk"), settings.afternoonRewardRisk);
            settings.marketIntradayMomentumMinOpenMoveTicks = parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumMinOpenMoveTicks"), settings.marketIntradayMomentumMinOpenMoveTicks);
            settings.marketIntradayMomentumMinLateMoveTicks = parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumMinLateMoveTicks"), settings.marketIntradayMomentumMinLateMoveTicks);
            settings.marketIntradayMomentumMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumMinVolumeRatio"), settings.marketIntradayMomentumMinVolumeRatio);
            settings.marketIntradayMomentumMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumMaxRiskTicks"), settings.marketIntradayMomentumMaxRiskTicks);
            settings.marketIntradayMomentumRewardRisk = parseDoubleOrDefault(ctx.queryParam("marketIntradayMomentumRewardRisk"), settings.marketIntradayMomentumRewardRisk);
            settings.allowKeltnerScalpLongs = parseBooleanOrDefault(ctx.queryParam("allowKeltnerScalpLongs"), settings.allowKeltnerScalpLongs);
            settings.allowKeltnerScalpShorts = parseBooleanOrDefault(ctx.queryParam("allowKeltnerScalpShorts"), settings.allowKeltnerScalpShorts);
            settings.keltnerAtrMultiplier = parseDoubleOrDefault(ctx.queryParam("keltnerAtrMultiplier"), settings.keltnerAtrMultiplier);
            settings.keltnerMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("keltnerMinVolumeRatio"), settings.keltnerMinVolumeRatio);
            settings.keltnerMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("keltnerMaxRiskTicks"), settings.keltnerMaxRiskTicks);
            settings.keltnerRewardRisk = parseDoubleOrDefault(ctx.queryParam("keltnerRewardRisk"), settings.keltnerRewardRisk);
            settings.keltnerMinBodyPct = parseDoubleOrDefault(ctx.queryParam("keltnerMinBodyPct"), settings.keltnerMinBodyPct);
            settings.keltnerMinTrendSlopeTicks = parseDoubleOrDefault(ctx.queryParam("keltnerMinTrendSlopeTicks"), settings.keltnerMinTrendSlopeTicks);
            settings.keltnerMinBandWidthTicks = parseDoubleOrDefault(ctx.queryParam("keltnerMinBandWidthTicks"), settings.keltnerMinBandWidthTicks);
            settings.keltnerMaxHoldBars = parseIntOrDefault(ctx.queryParam("keltnerMaxHoldBars"), settings.keltnerMaxHoldBars);
            settings.keltnerBucketMinutes = parseIntOrDefault(ctx.queryParam("keltnerBucketMinutes"), settings.keltnerBucketMinutes);
            settings.microScalpMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("microScalpMinVolumeRatio"), settings.microScalpMinVolumeRatio);
            settings.microScalpMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("microScalpMaxRiskTicks"), settings.microScalpMaxRiskTicks);
            settings.microScalpRewardRisk = parseDoubleOrDefault(ctx.queryParam("microScalpRewardRisk"), settings.microScalpRewardRisk);
            settings.microScalpMinBodyPct = parseDoubleOrDefault(ctx.queryParam("microScalpMinBodyPct"), settings.microScalpMinBodyPct);
            settings.microScalpMinTrendSlopeTicks = parseDoubleOrDefault(ctx.queryParam("microScalpMinTrendSlopeTicks"), settings.microScalpMinTrendSlopeTicks);
            settings.microScalpMaxHoldBars = parseIntOrDefault(ctx.queryParam("microScalpMaxHoldBars"), settings.microScalpMaxHoldBars);
            settings.microScalpBucketMinutes = parseIntOrDefault(ctx.queryParam("microScalpBucketMinutes"), settings.microScalpBucketMinutes);
            settings.allowMclEiaLongs = parseBooleanOrDefault(ctx.queryParam("allowMclEiaLongs"), settings.allowMclEiaLongs);
            settings.allowMclEiaShorts = parseBooleanOrDefault(ctx.queryParam("allowMclEiaShorts"), settings.allowMclEiaShorts);
            settings.mclEiaRangeStartMinute = parseIntOrDefault(ctx.queryParam("mclEiaRangeStartMinute"), settings.mclEiaRangeStartMinute);
            settings.mclEiaRangeEndMinute = parseIntOrDefault(ctx.queryParam("mclEiaRangeEndMinute"), settings.mclEiaRangeEndMinute);
            settings.mclEiaStartMinute = parseIntOrDefault(ctx.queryParam("mclEiaStartMinute"), settings.mclEiaStartMinute);
            settings.mclEiaEndMinute = parseIntOrDefault(ctx.queryParam("mclEiaEndMinute"), settings.mclEiaEndMinute);
            settings.mclEiaBreakoutBufferTicks = parseDoubleOrDefault(ctx.queryParam("mclEiaBreakoutBufferTicks"), settings.mclEiaBreakoutBufferTicks);
            settings.mclEiaStopTicks = parseDoubleOrDefault(ctx.queryParam("mclEiaStopTicks"), settings.mclEiaStopTicks);
            settings.mclEiaRewardRisk = parseDoubleOrDefault(ctx.queryParam("mclEiaRewardRisk"), settings.mclEiaRewardRisk);
            settings.mclEiaMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("mclEiaMinVolumeRatio"), settings.mclEiaMinVolumeRatio);
            settings.mclEiaMinBodyPct = parseDoubleOrDefault(ctx.queryParam("mclEiaMinBodyPct"), settings.mclEiaMinBodyPct);
            settings.mclEiaMaxHoldBars = parseIntOrDefault(ctx.queryParam("mclEiaMaxHoldBars"), settings.mclEiaMaxHoldBars);
            settings.allowMclCrudeOpenLongs = parseBooleanOrDefault(ctx.queryParam("allowMclCrudeOpenLongs"), settings.allowMclCrudeOpenLongs);
            settings.allowMclCrudeOpenShorts = parseBooleanOrDefault(ctx.queryParam("allowMclCrudeOpenShorts"), settings.allowMclCrudeOpenShorts);
            settings.mclCrudeOpenRangeStartMinute = parseIntOrDefault(ctx.queryParam("mclCrudeOpenRangeStartMinute"), settings.mclCrudeOpenRangeStartMinute);
            settings.mclCrudeOpenRangeEndMinute = parseIntOrDefault(ctx.queryParam("mclCrudeOpenRangeEndMinute"), settings.mclCrudeOpenRangeEndMinute);
            settings.mclCrudeOpenStartMinute = parseIntOrDefault(ctx.queryParam("mclCrudeOpenStartMinute"), settings.mclCrudeOpenStartMinute);
            settings.mclCrudeOpenEndMinute = parseIntOrDefault(ctx.queryParam("mclCrudeOpenEndMinute"), settings.mclCrudeOpenEndMinute);
            settings.mclCrudeOpenBreakoutBufferTicks = parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenBreakoutBufferTicks"), settings.mclCrudeOpenBreakoutBufferTicks);
            settings.mclCrudeOpenStopTicks = parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenStopTicks"), settings.mclCrudeOpenStopTicks);
            settings.mclCrudeOpenRewardRisk = parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenRewardRisk"), settings.mclCrudeOpenRewardRisk);
            settings.mclCrudeOpenMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenMinVolumeRatio"), settings.mclCrudeOpenMinVolumeRatio);
            settings.mclCrudeOpenMinBodyPct = parseDoubleOrDefault(ctx.queryParam("mclCrudeOpenMinBodyPct"), settings.mclCrudeOpenMinBodyPct);
            settings.mclCrudeOpenMaxHoldBars = parseIntOrDefault(ctx.queryParam("mclCrudeOpenMaxHoldBars"), settings.mclCrudeOpenMaxHoldBars);
            settings.allowMymIndexConfirmationLongs = parseBooleanOrDefault(ctx.queryParam("allowMymIndexConfirmationLongs"), settings.allowMymIndexConfirmationLongs);
            settings.allowMymIndexConfirmationShorts = parseBooleanOrDefault(ctx.queryParam("allowMymIndexConfirmationShorts"), settings.allowMymIndexConfirmationShorts);
            settings.mymIndexConfirmationStartMinute = parseIntOrDefault(ctx.queryParam("mymIndexConfirmationStartMinute"), settings.mymIndexConfirmationStartMinute);
            settings.mymIndexConfirmationEndMinute = parseIntOrDefault(ctx.queryParam("mymIndexConfirmationEndMinute"), settings.mymIndexConfirmationEndMinute);
            settings.mymIndexConfirmationBucketMinutes = parseIntOrDefault(ctx.queryParam("mymIndexConfirmationBucketMinutes"), settings.mymIndexConfirmationBucketMinutes);
            settings.mymIndexConfirmationLookbackBars = parseIntOrDefault(ctx.queryParam("mymIndexConfirmationLookbackBars"), settings.mymIndexConfirmationLookbackBars);
            settings.mymIndexConfirmationMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationMaxRiskTicks"), settings.mymIndexConfirmationMaxRiskTicks);
            settings.mymIndexConfirmationRewardRisk = parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationRewardRisk"), settings.mymIndexConfirmationRewardRisk);
            settings.mymIndexConfirmationMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationMinVolumeRatio"), settings.mymIndexConfirmationMinVolumeRatio);
            settings.mymIndexConfirmationMinBodyPct = parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationMinBodyPct"), settings.mymIndexConfirmationMinBodyPct);
            settings.mymIndexConfirmationMinTrendSlopeTicks = parseDoubleOrDefault(ctx.queryParam("mymIndexConfirmationMinTrendSlopeTicks"), settings.mymIndexConfirmationMinTrendSlopeTicks);
            settings.mymIndexConfirmationMaxHoldBars = parseIntOrDefault(ctx.queryParam("mymIndexConfirmationMaxHoldBars"), settings.mymIndexConfirmationMaxHoldBars);
            settings.allowMymOrbRetestLongs = parseBooleanOrDefault(ctx.queryParam("allowMymOrbRetestLongs"), settings.allowMymOrbRetestLongs);
            settings.allowMymOrbRetestShorts = parseBooleanOrDefault(ctx.queryParam("allowMymOrbRetestShorts"), settings.allowMymOrbRetestShorts);
            settings.mymOrbRetestStartMinute = parseIntOrDefault(ctx.queryParam("mymOrbRetestStartMinute"), settings.mymOrbRetestStartMinute);
            settings.mymOrbRetestEndMinute = parseIntOrDefault(ctx.queryParam("mymOrbRetestEndMinute"), settings.mymOrbRetestEndMinute);
            settings.mymOrbRetestBreakoutBufferTicks = parseDoubleOrDefault(ctx.queryParam("mymOrbRetestBreakoutBufferTicks"), settings.mymOrbRetestBreakoutBufferTicks);
            settings.mymOrbRetestRetestTicks = parseDoubleOrDefault(ctx.queryParam("mymOrbRetestRetestTicks"), settings.mymOrbRetestRetestTicks);
            settings.mymOrbRetestMaxRiskTicks = parseDoubleOrDefault(ctx.queryParam("mymOrbRetestMaxRiskTicks"), settings.mymOrbRetestMaxRiskTicks);
            settings.mymOrbRetestRewardRisk = parseDoubleOrDefault(ctx.queryParam("mymOrbRetestRewardRisk"), settings.mymOrbRetestRewardRisk);
            settings.mymOrbRetestMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("mymOrbRetestMinVolumeRatio"), settings.mymOrbRetestMinVolumeRatio);
            settings.mymOrbRetestMinBodyPct = parseDoubleOrDefault(ctx.queryParam("mymOrbRetestMinBodyPct"), settings.mymOrbRetestMinBodyPct);
            settings.mymOrbRetestMaxHoldBars = parseIntOrDefault(ctx.queryParam("mymOrbRetestMaxHoldBars"), settings.mymOrbRetestMaxHoldBars);
            settings.maxInitialRiskTicks = parseDoubleOrDefault(ctx.queryParam("maxInitialRiskTicks"), settings.maxInitialRiskTicks);
            settings.enableAdaptiveExits = parseBooleanOrDefault(ctx.queryParam("enableAdaptiveExits"), settings.enableAdaptiveExits);
            settings.adaptiveMinVolumeRatio = parseDoubleOrDefault(ctx.queryParam("adaptiveMinVolumeRatio"), settings.adaptiveMinVolumeRatio);
            settings.adaptiveMinBodyPct = parseDoubleOrDefault(ctx.queryParam("adaptiveMinBodyPct"), settings.adaptiveMinBodyPct);
            settings.adaptiveTrendTargetBoost = parseDoubleOrDefault(ctx.queryParam("adaptiveTrendTargetBoost"), settings.adaptiveTrendTargetBoost);
            settings.adaptiveVolumeTargetBoost = parseDoubleOrDefault(ctx.queryParam("adaptiveVolumeTargetBoost"), settings.adaptiveVolumeTargetBoost);
            settings.adaptiveBodyTargetBoost = parseDoubleOrDefault(ctx.queryParam("adaptiveBodyTargetBoost"), settings.adaptiveBodyTargetBoost);
            settings.adaptiveMaxRewardRisk = parseDoubleOrDefault(ctx.queryParam("adaptiveMaxRewardRisk"), settings.adaptiveMaxRewardRisk);
            settings.enableEarlyLossCut = parseBooleanOrDefault(ctx.queryParam("enableEarlyLossCut"), settings.enableEarlyLossCut);
            settings.earlyLossCutBars = parseIntOrDefault(ctx.queryParam("earlyLossCutBars"), settings.earlyLossCutBars);
            settings.earlyLossCutR = parseDoubleOrDefault(ctx.queryParam("earlyLossCutR"), settings.earlyLossCutR);
            settings.earlyLossCutMinFavorableR = parseDoubleOrDefault(ctx.queryParam("earlyLossCutMinFavorableR"), settings.earlyLossCutMinFavorableR);
            settings.managedStopBreakevenTriggerR = parseDoubleOrDefault(ctx.queryParam("managedStopBreakevenTriggerR"), settings.managedStopBreakevenTriggerR);
            settings.managedStopTrailTriggerR = parseDoubleOrDefault(ctx.queryParam("managedStopTrailTriggerR"), settings.managedStopTrailTriggerR);
            settings.managedStopTrailDistanceR = parseDoubleOrDefault(ctx.queryParam("managedStopTrailDistanceR"), settings.managedStopTrailDistanceR);
            settings.managedStopMinTrailTicks = parseDoubleOrDefault(ctx.queryParam("managedStopMinTrailTicks"), settings.managedStopMinTrailTicks);
            settings.enableManagedGivebackExit = parseBooleanOrDefault(ctx.queryParam("enableManagedGivebackExit"), settings.enableManagedGivebackExit);
            settings.managedGivebackTriggerR = parseDoubleOrDefault(ctx.queryParam("managedGivebackTriggerR"), settings.managedGivebackTriggerR);
            settings.managedGivebackR = parseDoubleOrDefault(ctx.queryParam("managedGivebackR"), settings.managedGivebackR);
            settings.managedGivebackMinBars = parseIntOrDefault(ctx.queryParam("managedGivebackMinBars"), settings.managedGivebackMinBars);
            settings.openMaeRiskMultiplier = parseDoubleOrDefault(ctx.queryParam("openMaeRiskMultiplier"), settings.openMaeRiskMultiplier);

	            if (FuturesManager.saveFuturesStrategySettings(symbol, slot, settings)) {
	                ctx.contentType("application/json").result(FuturesManager.getFuturesStrategySettingsJson(symbol, slot));
	            } else {
	                ctx.status(500).contentType("application/json").result("{\"message\":\"Failed to save futures strategy settings.\"}");
	            }
	        });

        app.post("/api/futures/strategy-configs/copy-to-live", ctx -> {
            ctx.status(410).contentType("application/json").result("{\"success\":false,\"message\":\"Legacy copy-to-live is disabled. Save or select a named Strategy Config preset instead.\"}");
        });

        app.get("/api/futures/risk", ctx -> {
            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            ctx.contentType("application/json").result(FuturesManager.getFuturesRiskSettingsJson(symbol));
        });

        app.get("/api/futures/funded-rule-profiles", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getFundedRuleProfilesJson());
        });

        app.post("/api/futures/risk", ctx -> {
            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            FuturesManager.FuturesRiskSettings settings = FuturesManager.loadFuturesRiskSettings(symbol);
            settings.accountSize = parseDoubleOrDefault(ctx.queryParam("accountSize"), settings.accountSize);
            settings.maxTrailingDrawdown = parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), settings.maxTrailingDrawdown);
            settings.dailyLossLimit = parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), settings.dailyLossLimit);
            settings.maxRiskPerTrade = parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), settings.maxRiskPerTrade);
            settings.maxContracts = parseIntOrDefault(ctx.queryParam("maxContracts"), settings.maxContracts);
            settings.commissionPerContract = parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), settings.commissionPerContract);
            settings.slippageTicks = parseDoubleOrDefault(ctx.queryParam("slippageTicks"), settings.slippageTicks);
            settings.profitTarget = parseDoubleOrDefault(ctx.queryParam("profitTarget"), settings.profitTarget);

            if (FuturesManager.saveFuturesRiskSettings(symbol, settings)) {
                ctx.contentType("application/json").result(FuturesManager.getFuturesRiskSettingsJson(symbol));
            } else {
                ctx.status(500).contentType("application/json").result("{\"message\":\"Failed to save futures risk settings.\"}");
            }
        });

        app.get("/api/futures/strategy/lab", ctx -> {
            String json = FuturesManager.getStrategyLabJson(
                ctx.queryParam("symbols"),
                ctx.queryParam("startDate"),
                ctx.queryParam("endDate"),
                    parseDoubleOrDefault(ctx.queryParam("accountSize"), 50000.0),
                    parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), 2500.0),
                    parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), 500.0),
	                parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), 400.0),
	                parseIntOrDefault(ctx.queryParam("maxContracts"), 12),
                    parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), 1.24),
                    parseDoubleOrDefault(ctx.queryParam("slippageTicks"), 1.0),
                    parseBooleanOrDefault(ctx.queryParam("useSavedRisk"), false)
            );
            ctx.contentType("application/json").result(json);
        });

        app.get("/api/futures/strategy/diagnostics", ctx -> {
            String json = FuturesManager.getStrategyDiagnosticsJson(
                ctx.queryParam("symbol"),
                ctx.queryParam("startDate"),
                ctx.queryParam("endDate"),
                parseDoubleOrDefault(ctx.queryParam("accountSize"), 50000.0),
                parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), 2500.0),
                parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), 500.0),
                parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), 400.0),
                parseIntOrDefault(ctx.queryParam("maxContracts"), 12),
                parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), 1.24),
                parseDoubleOrDefault(ctx.queryParam("slippageTicks"), 1.0),
                parseBooleanOrDefault(ctx.queryParam("useSavedRisk"), false)
            );
            ctx.contentType("application/json").result(json);
        });

        app.get("/api/futures/execution-options", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getExecutionOptionsJson());
        });

        app.get("/api/futures/connections", ctx -> {
            ctx.contentType("application/json").result(FuturesConnectionManager.getConnectionsJson());
        });

        app.get("/api/futures/connections/requirements", ctx -> {
            ctx.contentType("application/json").result(FuturesConnectionManager.getRequirementsJson());
        });

        app.post("/api/futures/connections/{provider}", ctx -> {
            String provider = ctx.pathParam("provider");
            boolean saved = FuturesConnectionManager.saveConnection(
                provider,
                parseBooleanOrDefault(requestParam(ctx, "enabled"), true),
                requestParam(ctx, "baseUrl"),
                requestParam(ctx, "environment"),
                requestParam(ctx, "username"),
                requestParam(ctx, "apiKey"),
                requestParam(ctx, "password"),
                requestParam(ctx, "secret"),
                requestParam(ctx, "appId"),
                requestParam(ctx, "appVersion"),
                requestParam(ctx, "cid"),
                requestParam(ctx, "accountId"),
                requestParam(ctx, "accountSpec"),
                requestParam(ctx, "dataset"),
                requestParam(ctx, "schema"),
                requestParam(ctx, "symbols"),
                requestParam(ctx, "marketHubUrl"),
                requestParam(ctx, "userHubUrl")
            );

            if (saved) {
                ctx.contentType("application/json").result(FuturesConnectionManager.getConnectionsJson());
            } else {
                ctx.status(500).contentType("application/json").result("{\"message\":\"Failed to save futures connection.\"}");
            }
        });

        app.post("/api/futures/connections/{provider}/test", ctx -> {
            ctx.contentType("application/json").result(FuturesConnectionManager.testConnection(ctx.pathParam("provider")));
        });

        app.post("/api/futures/topstepx/sync-readonly", ctx -> {
            ctx.contentType("application/json").result(FuturesConnectionManager.syncTopstepxReadOnly());
        });

        app.post("/api/futures/market-data/databento/import", ctx -> {
            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String startDate = valueOrDefault(ctx.queryParam("startDate"), LocalDate.now().minusYears(1).toString());
            String endDate = valueOrDefault(ctx.queryParam("endDate"), LocalDate.now().toString());
            String schema = valueOrDefault(ctx.queryParam("schema"), "ohlcv-1m");
            ctx.contentType("application/json").result(FuturesConnectionManager.importDatabentoBars(symbol, startDate, endDate, schema));
        });

        app.post("/api/futures/market-data/update-backtest-data", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS + ",GC");
            String startDate = valueOrDefault(ctx.queryParam("startDate"), LocalDate.now().minusYears(1).toString());
            String endDate = valueOrDefault(ctx.queryParam("endDate"), LocalDate.now().toString());
            String schema = valueOrDefault(ctx.queryParam("schema"), "ohlcv-1m");
            String result = FuturesConnectionManager.updateBacktestData(symbols, startDate, endDate, schema);
            if (result.contains("\"success\":false")) {
                ctx.status(updateBacktestDataErrorStatus(result));
            }
            ctx.contentType("application/json").result(result);
        });

        app.post("/api/futures/market-data/topstepx/import", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS + ",GC");
            String startDate = valueOrDefault(ctx.queryParam("startDate"), LocalDate.now().minusYears(1).toString());
            String endDate = valueOrDefault(ctx.queryParam("endDate"), LocalDate.now().toString());
            int maxContractsPerSymbol = parseIntOrDefault(ctx.queryParam("maxContractsPerSymbol"), 1);
            ctx.contentType("application/json").result(FuturesConnectionManager.importTopstepxBars(symbols, startDate, endDate, maxContractsPerSymbol));
        });

        app.post("/api/futures/market-data/rebuild-derived", ctx -> {
            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            ctx.contentType("application/json").result(FuturesConnectionManager.rebuildDerivedFuturesData(symbol));
        });

        app.post("/api/futures/backtests/generate", ctx -> {
            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String startDate = ctx.queryParam("startDate");
            String endDate = ctx.queryParam("endDate");
            String fundedProfile = valueOrDefault(ctx.queryParam("fundedProfile"), "CUSTOM");
            FuturesManager.FuturesRiskSettings savedRisk = FuturesManager.loadFuturesRiskSettings(symbol);
                double accountSize = parseDoubleOrDefault(ctx.queryParam("accountSize"), savedRisk.accountSize);
                double maxTrailingDrawdown = parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), savedRisk.maxTrailingDrawdown);
                double dailyLossLimit = parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), savedRisk.dailyLossLimit);
	            double maxRiskPerTrade = parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), savedRisk.maxRiskPerTrade);
	            int maxContracts = parseIntOrDefault(ctx.queryParam("maxContracts"), savedRisk.maxContracts);
                double commissionPerContract = parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), savedRisk.commissionPerContract);
                double slippageTicks = parseDoubleOrDefault(ctx.queryParam("slippageTicks"), savedRisk.slippageTicks);
                double profitTarget = parseDoubleOrDefault(ctx.queryParam("profitTarget"), savedRisk.profitTarget);

            int backtestId = FuturesManager.generateBacktest(
                symbol,
                startDate,
                endDate,
                accountSize,
                maxTrailingDrawdown,
                dailyLossLimit,
                maxRiskPerTrade,
                maxContracts,
                commissionPerContract,
                slippageTicks,
                profitTarget,
                fundedProfile
            );

            if (backtestId > 0) {
                ctx.status(201).contentType("application/json").result("{\"backtestId\":" + backtestId + "}");
            } else {
                ctx.status(500).contentType("application/json").result("{\"message\":\"Failed to generate futures backtest.\"}");
            }
        });

        app.get("/api/futures/portfolio-backtests/default-config", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getPortfolioBacktestDefaultConfigJson());
        });

        app.post("/api/futures/portfolio-backtests/generate", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS);
            String startDate = ctx.queryParam("startDate");
            String endDate = ctx.queryParam("endDate");
            int sourcePortfolioBacktestId = parseIntOrDefault(ctx.queryParam("sourcePortfolioBacktestId"), 0);
            String firstSymbol = symbols.split(",")[0];
            FuturesManager.FuturesRiskSettings savedRisk = FuturesManager.loadFuturesRiskSettings(firstSymbol);
            double accountSize = parseDoubleOrDefault(ctx.queryParam("accountSize"), savedRisk.accountSize);
            double maxTrailingDrawdown = parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), savedRisk.maxTrailingDrawdown);
            double dailyLossLimit = parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), savedRisk.dailyLossLimit);
            double maxRiskPerTrade = parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), savedRisk.maxRiskPerTrade);
            int maxContracts = parseIntOrDefault(ctx.queryParam("maxContracts"), savedRisk.maxContracts);
            double commissionPerContract = parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), savedRisk.commissionPerContract);
            double slippageTicks = parseDoubleOrDefault(ctx.queryParam("slippageTicks"), savedRisk.slippageTicks);
            int maxOpenPositions = parseIntOrDefault(ctx.queryParam("maxOpenPositions"), 1);
            int maxAggregateContracts = parseIntOrDefault(ctx.queryParam("maxAggregateContracts"), maxContracts * Math.max(1, symbols.split(",").length));
            double maxAggregateMiniUnits = parseDoubleOrDefault(ctx.queryParam("maxAggregateMiniUnits"), 0.0);
	            boolean useSavedRisk = parseBooleanOrDefault(ctx.queryParam("useSavedRisk"), true);
	            double profitTarget = parseDoubleOrDefault(ctx.queryParam("profitTarget"), savedRisk.profitTarget);
	            String fundedProfile = valueOrDefault(ctx.queryParam("fundedProfile"), "CUSTOM");
	            boolean continueAfterRuleViolation = parseBooleanOrDefault(ctx.queryParam("continueAfterRuleViolation"), false);
	            String strategyPreset = valueOrDefault(ctx.queryParam("strategyPreset"), "94k");
	            String presetValidationMessage = FuturesManager.validateStrategyPresetForSymbols(strategyPreset, symbols);
	            if (!presetValidationMessage.isEmpty()) {
	                ctx.status(400).contentType("application/json").result("{\"message\":\"" + presetValidationMessage.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
	                return;
	            }

	            int backtestId = FuturesManager.generatePortfolioBacktest(
	                symbols,
                startDate,
                endDate,
                accountSize,
                maxTrailingDrawdown,
                dailyLossLimit,
                maxRiskPerTrade,
                maxContracts,
                commissionPerContract,
                slippageTicks,
                maxOpenPositions,
                maxAggregateContracts,
                maxAggregateMiniUnits,
	                useSavedRisk,
	                profitTarget,
	                fundedProfile,
	                strategyPreset,
	                sourcePortfolioBacktestId,
	                continueAfterRuleViolation
	            );

            if (backtestId > 0) {
                ctx.status(201).contentType("application/json").result("{\"portfolioBacktestId\":" + backtestId + "}");
            } else {
                ctx.status(500).contentType("application/json").result("{\"message\":\"Failed to generate futures portfolio backtest.\"}");
            }
        });

        app.get("/api/futures/backtests", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getBacktestsJson());
        });

        app.get("/api/futures/portfolio-backtests", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getPortfolioBacktestsJson());
        });

        app.get("/api/futures/backtests/{id}/trades", ctx -> {
            int backtestId;
            try {
                backtestId = Integer.parseInt(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid futures backtest ID.");
                return;
            }
            ctx.contentType("application/json").result(FuturesManager.getBacktestTradesJson(backtestId));
        });

        app.get("/api/futures/portfolio-backtests/{id}/trades", ctx -> {
            int backtestId;
            try {
                backtestId = Integer.parseInt(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid futures portfolio backtest ID.");
                return;
            }
            ctx.contentType("application/json").result(FuturesManager.getPortfolioBacktestTradesJson(backtestId));
        });

        app.get("/api/futures/backtests/{id}/segments", ctx -> {
            int backtestId;
            try {
                backtestId = Integer.parseInt(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid futures backtest ID.");
                return;
            }
            ctx.contentType("application/json").result(FuturesManager.getBacktestSegmentsJson(backtestId));
        });

        app.get("/api/futures/portfolio-backtests/{id}/segments", ctx -> {
            int backtestId;
            try {
                backtestId = Integer.parseInt(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid futures portfolio backtest ID.");
                return;
            }
            ctx.contentType("application/json").result(FuturesManager.getPortfolioBacktestSegmentsJson(backtestId));
        });

        app.get("/api/futures/portfolio-backtests/{id}/symbols", ctx -> {
            int backtestId;
            try {
                backtestId = Integer.parseInt(ctx.pathParam("id"));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid futures portfolio backtest ID.");
                return;
            }
            ctx.contentType("application/json").result(FuturesManager.getPortfolioBacktestSymbolsJson(backtestId));
        });

        app.post("/api/futures/backtests/clear", ctx -> {
            if (FuturesManager.clearBacktests()) {
                ctx.status(204);
            } else {
                ctx.status(500).result("Failed to clear futures backtests.");
            }
        });

        app.post("/api/futures/portfolio-backtests/clear", ctx -> {
            if (FuturesManager.clearPortfolioBacktests()) {
                ctx.status(204);
            } else {
                ctx.status(500).result("Failed to clear futures portfolio backtests.");
            }
        });

        app.get("/api/futures/live/status", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getLiveStatusJson());
        });

        app.get("/api/futures/live/sidebar-status", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getLiveSidebarStatusJson());
        });

        app.get("/api/futures/live/strategy-snapshot", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getLiveStrategySnapshotJson());
        });

        app.post("/api/futures/live/strategy-snapshot", ctx -> {
            ctx.status(410).contentType("application/json").result("{\"success\":false,\"message\":\"Legacy copy-to-live is disabled. Select a Strategy Config preset before starting the live bot.\"}");
        });

        app.post("/api/futures/live/topstepx/order-dry-run", ctx -> {
            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String side = valueOrDefault(ctx.queryParam("side"), "LONG");
            int contracts = parseIntOrDefault(ctx.queryParam("contracts"), 1);
            double entryPrice = parseDoubleOrDefault(ctx.queryParam("entryPrice"), 0.0);
            double stopPrice = parseDoubleOrDefault(ctx.queryParam("stopPrice"), 0.0);
            double targetPrice = parseDoubleOrDefault(ctx.queryParam("targetPrice"), 0.0);
            String reason = valueOrDefault(ctx.queryParam("reason"), "manual dry-run");
            ctx.contentType("application/json").result(FuturesManager.dryRunTopstepxOrder(symbol, side, contracts, entryPrice, stopPrice, targetPrice, reason));
        });

        app.post("/api/futures/live/topstepx/order-submit", ctx -> {
            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String side = valueOrDefault(ctx.queryParam("side"), "LONG");
            int contracts = parseIntOrDefault(ctx.queryParam("contracts"), 1);
            double entryPrice = parseDoubleOrDefault(ctx.queryParam("entryPrice"), 0.0);
            double stopPrice = parseDoubleOrDefault(ctx.queryParam("stopPrice"), 0.0);
            double targetPrice = parseDoubleOrDefault(ctx.queryParam("targetPrice"), 0.0);
            String reason = valueOrDefault(ctx.queryParam("reason"), "live strategy order");
            ctx.contentType("application/json").result(FuturesManager.submitTopstepxPracticeOrder(symbol, side, contracts, entryPrice, stopPrice, targetPrice, reason));
        });

        app.post("/api/futures/live/dry-run-cycle", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.runLiveDryRunCycle());
        });

        app.get("/api/futures/live/pipeline-self-test", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.runLivePipelineSelfTestJson());
        });

        app.get("/api/futures/live/backtest-parity-self-test", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.runLiveBacktestParitySelfTestJson());
        });

        app.get("/api/futures/live/decisions", ctx -> {
            int sessionId = parseIntOrDefault(ctx.queryParam("sessionId"), 0);
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 100);
            String accountId = valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveSignalDecisionsJson(sessionId, limit, accountId));
        });

        app.get("/api/futures/live/thinking", ctx -> {
            int sessionId = parseIntOrDefault(ctx.queryParam("sessionId"), 0);
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 100);
            ctx.contentType("application/json").result(FuturesManager.getLiveThinkingJson(sessionId, limit));
        });

        app.post("/api/futures/live/thinking/clear", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.clearLiveThinkingLogJson());
        });

        app.get("/api/futures/live/metrics", ctx -> {
            String accountId = valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveMetricsJson(accountId));
        });

        app.get("/api/futures/live/marks", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS);
            String timeframe = valueOrDefault(ctx.queryParam("timeframe"), "1m");
            String accountId = valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveMarksJson(symbols, timeframe, accountId));
        });

        app.get("/api/futures/live/chart", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS);
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 240);
            ctx.contentType("application/json").result(FuturesManager.getLiveChartJson(symbols, limit));
        });

        app.get("/api/futures/live/monitor", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS);
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 180);
            String timeframe = valueOrDefault(ctx.queryParam("timeframe"), "1m");
            ctx.contentType("application/json").result(FuturesManager.getLiveMonitorJson(symbols, limit, timeframe));
        });

        app.get("/api/futures/live/strategy-diagnostics", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS);
            String strategyPreset = valueOrDefault(ctx.queryParam("strategyPreset"), "94k");
            String source = valueOrDefault(ctx.queryParam("source"), "live");
            String startDate = valueOrDefault(ctx.queryParam("startDate"), "");
            String endDate = valueOrDefault(ctx.queryParam("endDate"), "");
            int startMinute = parseIntOrDefault(ctx.queryParam("startMinute"), 660);
            int endMinute = parseIntOrDefault(ctx.queryParam("endMinute"), 870);
            ctx.contentType("application/json").result(FuturesManager.getLiveStrategyFilterDiagnosticsJson(
                symbols,
                strategyPreset,
                source,
                startDate,
                endDate,
                startMinute,
                endMinute
            ));
        });

        app.get("/api/futures/live/orders", ctx -> {
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 50);
            String accountId = valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveOrderLedgerJson(limit, accountId));
        });

        app.get("/api/futures/live/trade-cache", ctx -> {
            String accountId = valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveTradeCacheJson(accountId));
        });

        app.post("/api/futures/live/trade-cache", ctx -> {
            String accountId = valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.saveLiveTradeCacheJson(accountId, ctx.body()));
        });

        app.get("/api/futures/live/risk-events", ctx -> {
            int sessionId = parseIntOrDefault(ctx.queryParam("sessionId"), 0);
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 50);
            ctx.contentType("application/json").result(FuturesManager.getLiveRiskEventsJson(sessionId, limit));
        });

        app.get("/api/futures/live/realtime/status", ctx -> {
            ctx.contentType("application/json").result(ProjectXRealtimeManager.getStatusJson());
        });

        app.get("/api/futures/live/realtime/plan", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS);
            boolean includeDepth = parseBooleanOrDefault(ctx.queryParam("includeDepth"), false);
            ctx.contentType("application/json").result(ProjectXRealtimeManager.getPlanJson(symbols, includeDepth));
        });

        app.get("/api/futures/live/realtime/events", ctx -> {
            int limit = parseIntOrDefault(ctx.queryParam("limit"), 100);
            ctx.contentType("application/json").result(ProjectXRealtimeManager.getEventsJson(limit));
        });

        app.post("/api/futures/live/realtime/start", ctx -> {
            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS);
            boolean includeDepth = parseBooleanOrDefault(ctx.queryParam("includeDepth"), false);
            boolean confirmed = parseBooleanOrDefault(ctx.queryParam("confirmed"), false);
            ctx.contentType("application/json").result(ProjectXRealtimeManager.startReadOnly(symbols, includeDepth, confirmed));
        });

        app.post("/api/futures/live/realtime/stop", ctx -> {
            ctx.contentType("application/json").result(ProjectXRealtimeManager.stopReadOnly());
        });

	        app.post("/api/futures/live/start", ctx -> {
	            String symbol = valueOrDefault(ctx.queryParam("symbol"), "MNQ");
	            String symbols = valueOrDefault(ctx.queryParam("symbols"), DEFAULT_PORTFOLIO_SYMBOLS);
	            String executionMode = valueOrDefault(ctx.queryParam("executionMode"), "SIMULATED");
	            String fundedProfile = valueOrDefault(ctx.queryParam("fundedProfile"), "TOPSTEP_50K_RESEARCH");
	            String accountId = valueOrDefault(ctx.queryParam("accountId"), "");
	            String strategyPreset = valueOrDefault(ctx.queryParam("strategyPreset"), "94k");
            FuturesManager.FuturesRiskSettings savedRisk = FuturesManager.loadFuturesRiskSettings(symbol);
                double accountSize = parseDoubleOrDefault(ctx.queryParam("accountSize"), savedRisk.accountSize);
                double maxTrailingDrawdown = parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), savedRisk.maxTrailingDrawdown);
                double dailyLossLimit = parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), savedRisk.dailyLossLimit);
	            double maxRiskPerTrade = parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), savedRisk.maxRiskPerTrade);
	            int maxContracts = parseIntOrDefault(ctx.queryParam("maxContracts"), savedRisk.maxContracts);
                double commissionPerContract = parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), savedRisk.commissionPerContract);
                double slippageTicks = parseDoubleOrDefault(ctx.queryParam("slippageTicks"), savedRisk.slippageTicks);
                double profitTarget = parseDoubleOrDefault(ctx.queryParam("profitTarget"), savedRisk.profitTarget);
                int maxOpenPositions = parseIntOrDefault(ctx.queryParam("maxOpenPositions"), 1);
                int maxAggregateContracts = parseIntOrDefault(ctx.queryParam("maxAggregateContracts"), maxContracts);
                double maxAggregateMiniUnits = parseDoubleOrDefault(ctx.queryParam("maxAggregateMiniUnits"), 5.0);
                boolean entryOptimizerEnabled = parseBooleanOrDefault(ctx.queryParam("entryOptimizerEnabled"), false);
                boolean dtmEnabled = parseBooleanOrDefault(ctx.queryParam("dtmEnabled"), false);

            ctx.contentType("application/json").result(FuturesManager.startLive(
                symbol,
                executionMode,
                fundedProfile,
                accountId,
                accountSize,
                maxTrailingDrawdown,
                dailyLossLimit,
                maxRiskPerTrade,
                maxContracts,
                commissionPerContract,
                slippageTicks,
                profitTarget,
	                maxOpenPositions,
	                maxAggregateContracts,
	                maxAggregateMiniUnits,
	                symbols,
	                strategyPreset,
	                entryOptimizerEnabled,
	                dtmEnabled
	            ));
	        });

        app.post("/api/futures/live/stop", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.stopLive());
        });

    }

    private static void authorizeApiRequest(Context ctx, AccountManager accountManager) {
        String path = ctx.path();
        if (isPublicApiPath(path) || "OPTIONS".equalsIgnoreCase(ctx.method())) {
            return;
        }

        if (!REQUIRE_APP_AUTH) {
            ctx.attribute("accountId", accountManager.getAccountId(PRIMARY_ACCOUNT_EMAIL));
            ctx.attribute("accountEmail", PRIMARY_ACCOUNT_EMAIL);
            ctx.attribute("accountRole", "admin");
            ctx.attribute("accountSessionExpiresAt", "");
            return;
        }

        AccountManager.AccountSession session = accountManager.getSession(extractBearerToken(ctx));
        if (session == null) {
            throw new UnauthorizedResponse("Authentication required.");
        }

        String requiredRole = requiredRoleFor(ctx.method(), path);
        if (!roleAllows(session.role, requiredRole)) {
            throw new ForbiddenResponse("Insufficient account role.");
        }

        ctx.attribute("accountId", session.accountId);
        ctx.attribute("accountEmail", session.email);
        ctx.attribute("accountRole", session.role);
        ctx.attribute("accountSessionExpiresAt", session.expiresAt);
    }

    private static boolean isPublicApiPath(String path) {
        return "/api/login".equals(path)
            || "/api/system/health".equals(path)
            || "/api/system/version".equals(path);
    }

    private static String requiredRoleFor(String method, String path) {
        if ("GET".equals(method)) {
            if ("/api/settings/broker".equals(path) || path.startsWith("/api/futures/connections")) {
                return "admin";
            }
            return "viewer";
        }

        if ("/api/logout".equals(path)
            || "/api/account/change-password".equals(path)
            || "/api/account/details".equals(path)) {
            return "viewer";
        }

        if (path.startsWith("/api/live-bot/")
            || path.startsWith("/api/futures/live/")
            || "/api/trade".equals(path)) {
            return "operator";
        }

        return "admin";
    }

    private static String backendUpdateStatusJson() {
        File script = new File(backendUpdateScriptPath()).getAbsoluteFile();
        File logFile = new File(backendUpdateLogPath()).getAbsoluteFile();
        return "{"
            + "\"enabled\":" + backendUpdateEnabled() + ","
            + "\"scriptPath\":" + jsonString(script.getAbsolutePath()) + ","
            + "\"scriptFound\":" + script.isFile() + ","
            + "\"logPath\":" + jsonString(logFile.getAbsolutePath())
            + "}";
    }

    private static void triggerBackendUpdate(Context ctx) {
        if (!backendUpdateEnabled()) {
            ctx.status(403).contentType("application/json").result("{"
                + "\"success\":false,"
                + "\"message\":\"Backend update is disabled. Set TRADINGBOT_ENABLE_BACKEND_UPDATE=true in live_backend/.env.\""
                + "}");
            return;
        }

        File script = new File(backendUpdateScriptPath()).getAbsoluteFile();
        if (!script.isFile()) {
            ctx.status(500).contentType("application/json").result("{"
                + "\"success\":false,"
                + "\"message\":" + jsonString("Update script was not found at " + script.getAbsolutePath())
                + "}");
            return;
        }

        File logFile = new File(backendUpdateLogPath()).getAbsoluteFile();
        File logDir = logFile.getParentFile();
        if (logDir != null) {
            logDir.mkdirs();
        }

        String command = "nohup /bin/bash " + shellQuote(script.getAbsolutePath())
            + " --from-ui >> " + shellQuote(logFile.getAbsolutePath())
            + " 2>&1 < /dev/null &";

        try {
            new ProcessBuilder("/bin/bash", "-lc", command).start();
            ctx.status(202).contentType("application/json").result("{"
                + "\"success\":true,"
                + "\"message\":\"Backend update started. The API may disconnect while the live backend restarts.\","
                + "\"logPath\":" + jsonString(logFile.getAbsolutePath())
                + "}");
        } catch (IOException e) {
            ctx.status(500).contentType("application/json").result("{"
                + "\"success\":false,"
                + "\"message\":" + jsonString("Failed to start backend update: " + e.getMessage())
                + "}");
        }
    }

    private static boolean backendUpdateEnabled() {
        String configured = System.getProperty("tradingbot.enableBackendUpdate");
        if (isBlank(configured)) {
            configured = System.getenv("TRADINGBOT_ENABLE_BACKEND_UPDATE");
        }
        return Boolean.parseBoolean(valueOrDefault(configured, "false"));
    }

    private static String backendUpdateScriptPath() {
        String scriptPath = System.getProperty("tradingbot.backendUpdateScript");
        if (isBlank(scriptPath)) {
            scriptPath = System.getenv("TRADINGBOT_BACKEND_UPDATE_SCRIPT");
        }
        return valueOrDefault(
            scriptPath,
            "/Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/update-live-backend.sh"
        );
    }

    private static String backendUpdateLogPath() {
        String logPath = System.getProperty("tradingbot.backendUpdateLog");
        if (isBlank(logPath)) {
            logPath = System.getenv("TRADINGBOT_BACKEND_UPDATE_LOG");
        }
        return valueOrDefault(
            logPath,
            "/Users/anishpatel/Documents/SoftwareProject/live_backend/logs/update-backend.log"
        );
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static boolean roleAllows(String actualRole, String requiredRole) {
        String actual = isBlank(actualRole) ? "viewer" : actualRole.trim().toLowerCase();
        String required = isBlank(requiredRole) ? "viewer" : requiredRole.trim().toLowerCase();

        if ("admin".equals(actual)) {
            return true;
        }
        if ("operator".equals(actual)) {
            return "operator".equals(required) || "viewer".equals(required);
        }
        return "viewer".equals(required);
    }

    private static String sessionJson(AccountManager.AccountSession session) {
        return "{"
            + "\"token\":" + jsonString(session.token) + ","
            + "\"email\":" + jsonString(session.email) + ","
            + "\"role\":" + jsonString(session.role) + ","
            + "\"expiresAt\":" + jsonString(session.expiresAt)
            + "}";
    }

    private static String extractBearerToken(Context ctx) {
        String header = ctx.header("Authorization");
        if (isBlank(header)) {
            return "";
        }

        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return "";
    }

    private static String authenticatedEmail(Context ctx) {
        String email = ctx.attribute("accountEmail");
        return isBlank(email) ? PRIMARY_ACCOUNT_EMAIL : email;
    }

    private static String authenticatedRole(Context ctx) {
        String role = ctx.attribute("accountRole");
        return isBlank(role) ? "viewer" : role;
    }

    private static String authenticatedExpiresAt(Context ctx) {
        String expiresAt = ctx.attribute("accountSessionExpiresAt");
        return isBlank(expiresAt) ? "" : expiresAt;
    }

    static String requestParam(Context ctx, String name) {
        try {
            return ctx.formParam(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String loginThrottleKey(Context ctx, String email) {
        String normalizedEmail = isBlank(email) ? "unknown" : email.trim().toLowerCase();
        String ip = valueOrDefault(ctx.ip(), "unknown");
        return ip + "|" + normalizedEmail;
    }

    private static boolean isLoginLocked(String key) {
        LoginAttempt attempt = LOGIN_ATTEMPTS.get(key);
        if (attempt == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (attempt.lockedUntilMs > now) {
            return true;
        }
        if (attempt.lockedUntilMs > 0L || now - attempt.firstFailureMs > LOGIN_FAILURE_WINDOW_MS) {
            LOGIN_ATTEMPTS.remove(key);
        }
        return false;
    }

    private static void recordFailedLogin(String key) {
        long now = System.currentTimeMillis();
        LOGIN_ATTEMPTS.compute(key, (ignored, attempt) -> {
            LoginAttempt next = attempt;
            if (next == null || now - next.firstFailureMs > LOGIN_FAILURE_WINDOW_MS) {
                next = new LoginAttempt();
                next.firstFailureMs = now;
            }

            next.failureCount++;
            if (next.failureCount >= MAX_LOGIN_FAILURES) {
                next.lockedUntilMs = now + LOGIN_LOCKOUT_MS;
            }
            return next;
        });
    }

    private static void clearLoginFailures(String key) {
        LOGIN_ATTEMPTS.remove(key);
    }

    private static String maskSecret(String value) {
        if (isBlank(value)) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "saved";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    static double parseDoubleOrDefault(String value, double defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    static String valueOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    static String resolveAccountEmail(String email) {
        return isBlank(email) ? PRIMARY_ACCOUNT_EMAIL : email.trim();
    }

    static String resolveAccountEmail(Context ctx, String email) {
        String authenticatedEmail = authenticatedEmail(ctx);
        String requestedEmail = isBlank(email) ? authenticatedEmail : email.trim();

        if (!requestedEmail.equalsIgnoreCase(authenticatedEmail) && !"admin".equals(authenticatedRole(ctx))) {
            throw new ForbiddenResponse("Cannot access another account.");
        }

        return requestedEmail;
    }

    private static String defaultAccountEmail() {
        String configuredEmail = System.getProperty("tradingbot.defaultAccountEmail");
        if (isBlank(configuredEmail)) {
            configuredEmail = System.getenv("TRADINGBOT_DEFAULT_ACCOUNT_EMAIL");
        }
        return isBlank(configuredEmail) ? "local@example.invalid" : configuredEmail.trim();
    }

    private static boolean configuredRequireAppAuth() {
        String configured = System.getProperty("tradingbot.requireAppAuth");
        if (isBlank(configured)) {
            configured = System.getenv("TRADINGBOT_REQUIRE_APP_AUTH");
        }
        return Boolean.parseBoolean(valueOrDefault(configured, "false"));
    }

    private static String configuredBindHost() {
        String bindHost = System.getProperty("tradingbot.bindHost");
        if (isBlank(bindHost)) {
            bindHost = System.getenv("TRADINGBOT_BIND_HOST");
        }
        return isBlank(bindHost) ? "127.0.0.1" : bindHost.trim();
    }

    private static String[] configuredCorsOrigins() {
        String origins = System.getProperty("tradingbot.corsOrigins");
        if (isBlank(origins)) {
            origins = System.getenv("TRADINGBOT_CORS_ORIGINS");
        }
        if (!isBlank(origins)) {
            String[] parts = origins.split(",");
            List<String> cleaned = new ArrayList<String>();
            for (String part : parts) {
                if (!isBlank(part)) {
                    cleaned.add(part.trim());
                }
            }
            if (!cleaned.isEmpty()) {
                return cleaned.toArray(new String[0]);
            }
        }
        return new String[] {
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5174",
            "http://localhost:8080",
            "http://127.0.0.1:8080"
        };
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String jsonString(String value) {
        String safeValue = value == null ? "" : value;
        safeValue = safeValue
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        return "\"" + safeValue + "\"";
    }

    private static int updateBacktestDataErrorStatus(String result) {
        String normalized = valueOrDefault(result, "").toLowerCase();
        if (normalized.contains("api key is missing") || normalized.contains("only ohlcv")) {
            return 400;
        }
        if (normalized.contains("unauthorized") || normalized.contains("forbidden")) {
            return 401;
        }
        if (normalized.contains("\"success\":true")) {
            return 207;
        }
        return 502;
    }

    private static String safeMessage(String message, String fallback) {
        String safe = valueOrDefault(message, fallback);
        return safe.length() > 500 ? safe.substring(0, 500) + "..." : safe;
    }

    static String normalizeTradeChartTimeframe(String timeframe) {
        if ("5Min".equalsIgnoreCase(timeframe)) {
            return "5Min";
        }
        if ("30Min".equalsIgnoreCase(timeframe)) {
            return "30Min";
        }
        if ("1Hour".equalsIgnoreCase(timeframe)) {
            return "1Hour";
        }
        return "1Min";
    }

    private static String buildTradeChartJson(ResultSet rs, String timeframe) throws SQLException {
        String symbol = AlpacaManager.normalizeSymbol(rs.getString("symbol"));
        String openedAt = rs.getString("openedAt");
        String closedAt = rs.getString("closedAt");
        String strategyCode = rs.getString("strategyCode");
        String strategyName = rs.getString("strategyName");
        String tradeNotes = rs.getString("tradeNotes");
        String side = rs.getString("side");
        double qty = rs.getDouble("qty");
        double entryPrice = rs.getDouble("entryPrice");
        double exitPrice = rs.getDouble("exitPrice");
        double pnl = rs.getDouble("pnl");
        LocalDate tradeDate = parseTradeDate(openedAt);
        LocalTime entryTime = parseTradeTime(openedAt);
        LocalTime exitTime = parseTradeTime(closedAt);
        List<AlpacaManager.CachedBar> candles = AlpacaManager.loadCachedBars(symbol, tradeDate, tradeDate, timeframe);
        List<AlpacaManager.CachedBar> oneMinuteCandles = "1Min".equals(timeframe)
            ? candles
            : AlpacaManager.loadCachedBars(symbol, tradeDate, tradeDate, "1Min");
        TradeChartStats stats = buildTradeChartStats(candles, entryTime, exitTime, entryPrice, exitPrice, side);
        TradeChartOverlays overlays = buildTradeChartOverlays(
            strategyCode,
            tradeNotes,
            oneMinuteCandles,
            entryPrice,
            exitPrice,
            stats
        );

        int durationMinutes = calculateDurationMinutes(entryTime, exitTime);
        double returnPct = calculateTradeReturnPct(side, entryPrice, exitPrice);

        StringBuilder json = new StringBuilder("{");
        json.append("\"timeframe\":").append(jsonString(timeframe)).append(",");
        json.append("\"trade\":{")
            .append("\"id\":").append(rs.getInt("tradeID")).append(",")
            .append("\"symbol\":").append(jsonString(symbol)).append(",")
            .append("\"strategyCode\":").append(jsonString(strategyCode)).append(",")
            .append("\"strategyName\":").append(jsonString(strategyName)).append(",")
            .append("\"side\":").append(jsonString(side)).append(",")
            .append("\"qty\":").append(qty).append(",")
            .append("\"entryTime\":").append(jsonString(openedAt)).append(",")
            .append("\"exitTime\":").append(jsonString(closedAt)).append(",")
            .append("\"entryPrice\":").append(entryPrice).append(",")
            .append("\"exitPrice\":").append(exitPrice).append(",")
            .append("\"pnl\":").append(pnl).append(",")
            .append("\"returnPct\":").append(roundToTwoDecimals(returnPct)).append(",")
            .append("\"durationMinutes\":").append(durationMinutes).append(",")
            .append("\"tradeNotes\":").append(jsonString(tradeNotes))
            .append("},");
        appendCandleJson(json, candles);
        json.append(",");
        appendMetricJson(json, stats);
        json.append(",");
        appendAnnotationJson(json, overlays.lines);
        json.append(",");
        appendZoneJson(json, overlays.zones);
        json.append(",");
        appendKeyPointJson(json, overlays.keyPoints);
        json.append("}");
        return json.toString();
    }

    private static LocalDate parseTradeDate(String value) {
        if (isBlank(value) || value.length() < 10) {
            return LocalDate.now();
        }

        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return LocalDate.now();
        }
    }

    private static LocalTime parseTradeTime(String value) {
        if (isBlank(value) || value.length() < 16) {
            return null;
        }

        try {
            return LocalTime.parse(value.substring(11, 16));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int calculateDurationMinutes(LocalTime entryTime, LocalTime exitTime) {
        if (entryTime == null || exitTime == null) {
            return 0;
        }

        long minutes = Duration.between(entryTime, exitTime).toMinutes();
        return (int) Math.max(0, minutes);
    }

    private static double calculateTradeReturnPct(String side, double entryPrice, double exitPrice) {
        if (entryPrice <= 0.0 || exitPrice <= 0.0) {
            return 0.0;
        }

        String normalizedSide = side == null ? "" : side.trim().toUpperCase();
        if ("SHORT".equals(normalizedSide)) {
            return ((entryPrice - exitPrice) / entryPrice) * 100.0;
        }
        return ((exitPrice - entryPrice) / entryPrice) * 100.0;
    }

    private static TradeChartStats buildTradeChartStats(
        List<AlpacaManager.CachedBar> candles,
        LocalTime entryTime,
        LocalTime exitTime,
        double entryPrice,
        double exitPrice,
        String side
    ) {
        TradeChartStats stats = new TradeChartStats();
        stats.sessionHigh = 0.0;
        stats.sessionLow = 0.0;
        stats.tradeHigh = 0.0;
        stats.tradeLow = 0.0;
        stats.barsHeld = 0;

        if (candles == null || candles.isEmpty()) {
            stats.tradeHigh = Math.max(entryPrice, exitPrice);
            stats.tradeLow = Math.min(nonZero(entryPrice, exitPrice), nonZero(exitPrice, entryPrice));
            return stats;
        }

        for (int i = 0; i < candles.size(); i++) {
            AlpacaManager.CachedBar bar = candles.get(i);
            if (stats.sessionHigh == 0.0 || bar.high > stats.sessionHigh) {
                stats.sessionHigh = bar.high;
            }
            if (stats.sessionLow == 0.0 || bar.low < stats.sessionLow) {
                stats.sessionLow = bar.low;
            }

            if (isWithinTradeWindow(bar.marketTime, entryTime, exitTime)) {
                stats.barsHeld++;
                if (stats.tradeHigh == 0.0 || bar.high > stats.tradeHigh) {
                    stats.tradeHigh = bar.high;
                }
                if (stats.tradeLow == 0.0 || bar.low < stats.tradeLow) {
                    stats.tradeLow = bar.low;
                }
            }
        }

        if (stats.tradeHigh == 0.0) {
            stats.tradeHigh = Math.max(entryPrice, exitPrice);
        }
        if (stats.tradeLow == 0.0) {
            stats.tradeLow = Math.min(nonZero(entryPrice, exitPrice), nonZero(exitPrice, entryPrice));
        }

        double favorableMove = calculateFavorableMove(side, entryPrice, stats.tradeHigh, stats.tradeLow);
        double adverseMove = calculateAdverseMove(side, entryPrice, stats.tradeHigh, stats.tradeLow);
        stats.mfePct = entryPrice <= 0.0 ? 0.0 : roundToTwoDecimals((favorableMove / entryPrice) * 100.0);
        stats.maePct = entryPrice <= 0.0 ? 0.0 : roundToTwoDecimals((-adverseMove / entryPrice) * 100.0);
        return stats;
    }

    private static boolean isWithinTradeWindow(LocalTime barTime, LocalTime entryTime, LocalTime exitTime) {
        if (barTime == null || entryTime == null) {
            return false;
        }
        if (barTime.isBefore(entryTime)) {
            return false;
        }
        return exitTime == null || !barTime.isAfter(exitTime);
    }

    private static double calculateFavorableMove(String side, double entryPrice, double tradeHigh, double tradeLow) {
        if (entryPrice <= 0.0) {
            return 0.0;
        }
        return "SHORT".equalsIgnoreCase(side)
            ? Math.max(0.0, entryPrice - tradeLow)
            : Math.max(0.0, tradeHigh - entryPrice);
    }

    private static double calculateAdverseMove(String side, double entryPrice, double tradeHigh, double tradeLow) {
        if (entryPrice <= 0.0) {
            return 0.0;
        }
        return "SHORT".equalsIgnoreCase(side)
            ? Math.max(0.0, tradeHigh - entryPrice)
            : Math.max(0.0, entryPrice - tradeLow);
    }

    private static double nonZero(double value, double fallback) {
        return value > 0.0 ? value : fallback;
    }

    private static TradeChartOverlays buildTradeChartOverlays(
        String strategyCode,
        String tradeNotes,
        List<AlpacaManager.CachedBar> oneMinuteCandles,
        double entryPrice,
        double exitPrice,
        TradeChartStats stats
    ) {
        TradeChartOverlays overlays = new TradeChartOverlays();
        String normalizedStrategy = strategyCode == null ? "" : strategyCode.trim().toUpperCase();
        String notes = tradeNotes == null ? "" : tradeNotes;

        overlays.lines.add(new ChartLine("entry", "Entry", "entry", entryPrice));
        overlays.lines.add(new ChartLine("exit", "Exit", "exit", exitPrice));
        overlays.lines.add(new ChartLine("trade-high", "Trade High", "trade-high", stats.tradeHigh));
        overlays.lines.add(new ChartLine("trade-low", "Trade Low", "trade-low", stats.tradeLow));
        overlays.lines.add(new ChartLine("session-high", "Session High", "session-high", stats.sessionHigh));
        overlays.lines.add(new ChartLine("session-low", "Session Low", "session-low", stats.sessionLow));

        appendBiasKeyPoint(overlays, notes);
        appendOrbOverlays(overlays, normalizedStrategy, notes, oneMinuteCandles);
        appendIfvgOverlays(overlays, normalizedStrategy, notes);
        appendVwapOverlays(overlays, normalizedStrategy, notes);
        appendGapGoOverlays(overlays, normalizedStrategy, notes);
        return overlays;
    }

    private static void appendBiasKeyPoint(TradeChartOverlays overlays, String notes) {
        Matcher matcher = BIAS_PATTERN.matcher(notes);
        if (matcher.find()) {
            overlays.keyPoints.add(new KeyPoint("Bias", capitalize(matcher.group(1)) + " from " + matcher.group(2).trim()));
        }
    }

    private static void appendOrbOverlays(
        TradeChartOverlays overlays,
        String strategyCode,
        String notes,
        List<AlpacaManager.CachedBar> oneMinuteCandles
    ) {
        boolean usesOrb = "ORB".equals(strategyCode) || "GAPGO".equals(strategyCode) || notes.toUpperCase().contains("ORB") || notes.toUpperCase().contains("RANGE HIGH");
        if (!usesOrb) {
            return;
        }

        int orbMinutes = parseOrbWindowMinutes(notes);
        NumberRange range = parseRange(ORB_RANGE_PATTERN, notes);
        if (range == null) {
            range = calculateOpeningRange(oneMinuteCandles, orbMinutes);
        }
        if (range == null || range.high <= range.low || range.low <= 0.0) {
            return;
        }

        String label = "GAPGO".equals(strategyCode) ? "Gap ORB Range" : "ORB Range";
        overlays.zones.add(new ChartZone("orb-range", label, "orb", range.low, range.high));
        overlays.lines.add(new ChartLine("orb-high", "ORB High", "orb-high", range.high));
        overlays.lines.add(new ChartLine("orb-low", "ORB Low", "orb-low", range.low));
        overlays.keyPoints.add(new KeyPoint(label, "$" + formatPrice(range.low) + " - $" + formatPrice(range.high)));
        overlays.keyPoints.add(new KeyPoint("ORB Window", orbMinutes + "m"));
    }

    private static void appendIfvgOverlays(TradeChartOverlays overlays, String strategyCode, String notes) {
        boolean usesIfvg = "IFVG".equals(strategyCode) || notes.toUpperCase().contains("IFVG") || notes.toUpperCase().contains("FLIPPED GAP");
        if (!usesIfvg) {
            return;
        }

        NumberRange range = parseRange(IFVG_RANGE_PATTERN, notes);
        if (range == null || range.high <= range.low || range.low <= 0.0) {
            return;
        }

        overlays.zones.add(new ChartZone("ifvg-zone", "IFVG Zone", "ifvg", range.low, range.high));
        overlays.lines.add(new ChartLine("ifvg-high", "IFVG High", "ifvg", range.high));
        overlays.lines.add(new ChartLine("ifvg-low", "IFVG Low", "ifvg", range.low));
        overlays.keyPoints.add(new KeyPoint("IFVG Zone", "$" + formatPrice(range.low) + " - $" + formatPrice(range.high)));
    }

    private static void appendVwapOverlays(TradeChartOverlays overlays, String strategyCode, String notes) {
        boolean usesVwap = "VWAP".equals(strategyCode) || notes.toUpperCase().contains("VWAP");
        if (!usesVwap) {
            return;
        }

        Matcher matcher = VWAP_LEVEL_PATTERN.matcher(notes);
        if (matcher.find()) {
            double vwap = parseDoubleOrDefault(matcher.group(1), 0.0);
            overlays.lines.add(new ChartLine("vwap-trigger", "Signal VWAP", "vwap", vwap));
            overlays.keyPoints.add(new KeyPoint("Signal VWAP", "$" + formatPrice(vwap)));
        } else {
            overlays.keyPoints.add(new KeyPoint("Signal VWAP", "Tracked on chart"));
        }
    }

    private static void appendGapGoOverlays(TradeChartOverlays overlays, String strategyCode, String notes) {
        boolean usesGapGo = "GAPGO".equals(strategyCode) || notes.toUpperCase().contains("GAP-AND-GO");
        if (!usesGapGo) {
            return;
        }

        Matcher previousCloseMatcher = PREVIOUS_CLOSE_PATTERN.matcher(notes);
        if (previousCloseMatcher.find()) {
            double previousClose = parseDoubleOrDefault(previousCloseMatcher.group(1), 0.0);
            overlays.lines.add(new ChartLine("previous-close", "Previous Close", "previous-close", previousClose));
            overlays.keyPoints.add(new KeyPoint("Previous Close", "$" + formatPrice(previousClose)));
        }

        Matcher openingGapMatcher = OPENING_GAP_PATTERN.matcher(notes);
        if (openingGapMatcher.find()) {
            overlays.keyPoints.add(new KeyPoint("Opening Gap", openingGapMatcher.group(1) + "%"));
        }
    }

    private static int parseOrbWindowMinutes(String notes) {
        Matcher matcher = ORB_WINDOW_PATTERN.matcher(notes == null ? "" : notes);
        if (matcher.find()) {
            return Math.max(1, parseIntOrDefault(matcher.group(1), 15));
        }
        return 15;
    }

    private static NumberRange parseRange(Pattern pattern, String notes) {
        Matcher matcher = pattern.matcher(notes == null ? "" : notes);
        if (!matcher.find()) {
            return null;
        }

        double first = parseDoubleOrDefault(matcher.group(1), 0.0);
        double second = parseDoubleOrDefault(matcher.group(2), 0.0);
        if (first <= 0.0 || second <= 0.0) {
            return null;
        }
        return new NumberRange(Math.min(first, second), Math.max(first, second));
    }

    private static NumberRange calculateOpeningRange(List<AlpacaManager.CachedBar> candles, int orbMinutes) {
        if (candles == null || candles.isEmpty()) {
            return null;
        }

        LocalTime rangeStart = LocalTime.of(9, 30);
        LocalTime rangeEnd = rangeStart.plusMinutes(Math.max(1, orbMinutes));
        double high = 0.0;
        double low = 0.0;

        for (int i = 0; i < candles.size(); i++) {
            AlpacaManager.CachedBar bar = candles.get(i);
            if (bar.marketTime.isBefore(rangeStart) || !bar.marketTime.isBefore(rangeEnd)) {
                continue;
            }
            if (high == 0.0 || bar.high > high) {
                high = bar.high;
            }
            if (low == 0.0 || bar.low < low) {
                low = bar.low;
            }
        }

        if (high <= 0.0 || low <= 0.0 || high <= low) {
            return null;
        }
        return new NumberRange(low, high);
    }

    private static void appendCandleJson(StringBuilder json, List<AlpacaManager.CachedBar> candles) {
        json.append("\"candles\":[");
        if (candles != null) {
            for (int i = 0; i < candles.size(); i++) {
                AlpacaManager.CachedBar bar = candles.get(i);
                if (i > 0) {
                    json.append(",");
                }
                json.append("{")
                    .append("\"time\":").append(jsonString(bar.displayTime)).append(",")
                    .append("\"date\":").append(jsonString(bar.marketDate.toString())).append(",")
                    .append("\"marketTime\":").append(jsonString(bar.marketTime.toString())).append(",")
                    .append("\"open\":").append(bar.open).append(",")
                    .append("\"high\":").append(bar.high).append(",")
                    .append("\"low\":").append(bar.low).append(",")
                    .append("\"close\":").append(bar.close).append(",")
                    .append("\"volume\":").append(bar.volume)
                    .append("}");
            }
        }
        json.append("]");
    }

    private static void appendMetricJson(StringBuilder json, TradeChartStats stats) {
        json.append("\"metrics\":{")
            .append("\"sessionHigh\":").append(roundToTwoDecimals(stats.sessionHigh)).append(",")
            .append("\"sessionLow\":").append(roundToTwoDecimals(stats.sessionLow)).append(",")
            .append("\"tradeHigh\":").append(roundToTwoDecimals(stats.tradeHigh)).append(",")
            .append("\"tradeLow\":").append(roundToTwoDecimals(stats.tradeLow)).append(",")
            .append("\"barsHeld\":").append(stats.barsHeld).append(",")
            .append("\"mfePct\":").append(roundToTwoDecimals(stats.mfePct)).append(",")
            .append("\"maePct\":").append(roundToTwoDecimals(stats.maePct))
            .append("}");
    }

    private static void appendAnnotationJson(StringBuilder json, List<ChartLine> lines) {
        json.append("\"annotations\":[");
        boolean appended = false;
        for (int i = 0; i < lines.size(); i++) {
            ChartLine line = lines.get(i);
            if (line == null || line.value <= 0.0) {
                continue;
            }
            if (appended) {
                json.append(",");
            }
            json.append("{")
                .append("\"key\":").append(jsonString(line.key)).append(",")
                .append("\"label\":").append(jsonString(line.label)).append(",")
                .append("\"kind\":").append(jsonString(line.kind)).append(",")
                .append("\"value\":").append(roundToTwoDecimals(line.value))
                .append("}");
            appended = true;
        }
        json.append("]");
    }

    private static void appendZoneJson(StringBuilder json, List<ChartZone> zones) {
        json.append("\"zones\":[");
        boolean appended = false;
        for (int i = 0; i < zones.size(); i++) {
            ChartZone zone = zones.get(i);
            if (zone == null || zone.high <= zone.low || zone.low <= 0.0) {
                continue;
            }
            if (appended) {
                json.append(",");
            }
            json.append("{")
                .append("\"key\":").append(jsonString(zone.key)).append(",")
                .append("\"label\":").append(jsonString(zone.label)).append(",")
                .append("\"kind\":").append(jsonString(zone.kind)).append(",")
                .append("\"low\":").append(roundToTwoDecimals(zone.low)).append(",")
                .append("\"high\":").append(roundToTwoDecimals(zone.high))
                .append("}");
            appended = true;
        }
        json.append("]");
    }

    private static void appendKeyPointJson(StringBuilder json, List<KeyPoint> keyPoints) {
        json.append("\"keyPoints\":[");
        boolean appended = false;
        for (int i = 0; i < keyPoints.size(); i++) {
            KeyPoint keyPoint = keyPoints.get(i);
            if (keyPoint == null || isBlank(keyPoint.label) || isBlank(keyPoint.value)) {
                continue;
            }
            if (appended) {
                json.append(",");
            }
            json.append("{")
                .append("\"label\":").append(jsonString(keyPoint.label)).append(",")
                .append("\"value\":").append(jsonString(keyPoint.value))
                .append("}");
            appended = true;
        }
        json.append("]");
    }

    private static double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String formatPrice(double value) {
        return String.format("%.2f", value);
    }

    private static String capitalize(String value) {
        if (isBlank(value)) {
            return "";
        }
        String trimmed = value.trim().toLowerCase();
        return trimmed.substring(0, 1).toUpperCase() + trimmed.substring(1);
    }

    private static class TradeChartStats {
        private double sessionHigh;
        private double sessionLow;
        private double tradeHigh;
        private double tradeLow;
        private double mfePct;
        private double maePct;
        private int barsHeld;
    }

    private static class TradeChartOverlays {
        private final List<ChartLine> lines = new ArrayList<ChartLine>();
        private final List<ChartZone> zones = new ArrayList<ChartZone>();
        private final List<KeyPoint> keyPoints = new ArrayList<KeyPoint>();
    }

    private static class ChartLine {
        private final String key;
        private final String label;
        private final String kind;
        private final double value;

        private ChartLine(String key, String label, String kind, double value) {
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.value = value;
        }
    }

    private static class ChartZone {
        private final String key;
        private final String label;
        private final String kind;
        private final double low;
        private final double high;

        private ChartZone(String key, String label, String kind, double low, double high) {
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.low = low;
            this.high = high;
        }
    }

    private static class KeyPoint {
        private final String label;
        private final String value;

        private KeyPoint(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private static class NumberRange {
        private final double low;
        private final double high;

        private NumberRange(double low, double high) {
            this.low = low;
            this.high = high;
        }
    }

    private static class LoginAttempt {
        int failureCount;
        long firstFailureMs;
        long lockedUntilMs;
    }
    
}
