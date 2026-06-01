package com.tradingbot;

import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.Javalin;
import java.util.ArrayList;
import java.util.List;

public class MainServer {
    static final String PRIMARY_ACCOUNT_EMAIL = defaultAccountEmail();
    private static final String APP_VERSION = System.getProperty("tradingbot.version", "local-dev");
    private static final String BUILD_ID = System.getProperty("tradingbot.build", "unversioned");
    private static final boolean REQUIRE_APP_AUTH = configuredRequireAppAuth();
    private static final long STARTED_AT_MS = System.currentTimeMillis();
    private static final String DEFAULT_PORTFOLIO_SYMBOLS = "MES,MNQ,NQ,MGC,ES,M2K,MYM,MCL";

    public static void main(String[] args) {
        DatabaseManager.initializeDatabase();
        FuturesManager.initializeStore();
        FuturesConnectionManager.initializeStore();
        ProjectXRealtimeManager.initializeStore();
        
        AccountManager accountManager = new AccountManager();
        
        String bindHost = configuredBindHost();
        int port = ApiRequestUtils.parseIntOrDefault(System.getProperty("tradingbot.port", System.getenv("TRADINGBOT_PORT")), 7070);

        Javalin app = Javalin.create(config -> {
            config.enableCorsForOrigin(configuredCorsOrigins());
        }).start(bindHost, port);

        app.before("/api/*", ctx -> ApiAuthSupport.authorizeApiRequest(ctx, accountManager, REQUIRE_APP_AUTH, PRIMARY_ACCOUNT_EMAIL));
        app.error(404, ctx -> {
            if (ctx.path().startsWith("/api/")) {
                ctx.contentType("application/json").result("{"
                    + "\"success\":false,"
                    + "\"errorCode\":\"API_ROUTE_NOT_FOUND\","
                    + "\"message\":" + ApiRequestUtils.jsonString("No API route matched " + ctx.method() + " " + ctx.path()) + ","
                    + "\"method\":" + ApiRequestUtils.jsonString(ctx.method()) + ","
                    + "\"path\":" + ApiRequestUtils.jsonString(ctx.path())
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
                    + "\"errorCode\":" + ApiRequestUtils.jsonString(errorCode) + ","
                    + "\"message\":" + ApiRequestUtils.jsonString(safeMessage(error.getMessage(), "Internal API error."))
                    + "}");
            } else {
                ctx.status(500).result(safeMessage(error.getMessage(), "Internal server error."));
            }
        });
        
        System.out.println("Backend Server is LIVE on http://" + bindHost + ":" + port);

        SystemRoutes.register(
            app,
            APP_VERSION,
            BUILD_ID,
            STARTED_AT_MS
        );
        
        AuthRoutes.register(
            app,
            accountManager,
            ApiAuthSupport::loginThrottleKey,
            ApiAuthSupport::isLoginLocked,
            ApiAuthSupport::recordFailedLogin,
            ApiAuthSupport::clearLoginFailures,
            ApiAuthSupport::sessionJson,
            ApiAuthSupport::authenticatedEmail,
            ApiAuthSupport::authenticatedRole,
            ApiAuthSupport::authenticatedExpiresAt,
            ApiAuthSupport::extractBearerToken
        );

        AccountRoutes.register(
            app,
            accountManager,
            ApiAuthSupport::resolveAccountEmail
        );

        LegacyEquityRoutes.register(app);

        FuturesMarketRoutes.register(app);

        FuturesStrategyRoutes.register(app);
        FuturesRiskRoutes.register(app);

        FuturesConnectionRoutes.register(
            app,
            DEFAULT_PORTFOLIO_SYMBOLS,
            MainServer::updateBacktestDataErrorStatus
        );

        FuturesBacktestRoutes.register(app, DEFAULT_PORTFOLIO_SYMBOLS);
        FuturesLiveRoutes.register(app, DEFAULT_PORTFOLIO_SYMBOLS);

    }

    static double parseDoubleOrDefault(String value, double defaultValue) {
        return ApiRequestUtils.parseDoubleOrDefault(value, defaultValue);
    }

    static int parseIntOrDefault(String value, int defaultValue) {
        return ApiRequestUtils.parseIntOrDefault(value, defaultValue);
    }

    static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        return ApiRequestUtils.parseBooleanOrDefault(value, defaultValue);
    }

    static String valueOrDefault(String value, String defaultValue) {
        return ApiRequestUtils.valueOrDefault(value, defaultValue);
    }

    static boolean isBlank(String value) {
        return ApiRequestUtils.isBlank(value);
    }

    static String jsonString(String value) {
        return ApiRequestUtils.jsonString(value);
    }

    private static String defaultAccountEmail() {
        String configuredEmail = System.getProperty("tradingbot.defaultAccountEmail");
        if (ApiRequestUtils.isBlank(configuredEmail)) {
            configuredEmail = System.getenv("TRADINGBOT_DEFAULT_ACCOUNT_EMAIL");
        }
        return ApiRequestUtils.isBlank(configuredEmail) ? "local@example.invalid" : configuredEmail.trim();
    }

    private static boolean configuredRequireAppAuth() {
        String configured = System.getProperty("tradingbot.requireAppAuth");
        if (ApiRequestUtils.isBlank(configured)) {
            configured = System.getenv("TRADINGBOT_REQUIRE_APP_AUTH");
        }
        return Boolean.parseBoolean(ApiRequestUtils.valueOrDefault(configured, "false"));
    }

    private static String configuredBindHost() {
        String bindHost = System.getProperty("tradingbot.bindHost");
        if (ApiRequestUtils.isBlank(bindHost)) {
            bindHost = System.getenv("TRADINGBOT_BIND_HOST");
        }
        return ApiRequestUtils.isBlank(bindHost) ? "127.0.0.1" : bindHost.trim();
    }

    private static String[] configuredCorsOrigins() {
        String origins = System.getProperty("tradingbot.corsOrigins");
        if (ApiRequestUtils.isBlank(origins)) {
            origins = System.getenv("TRADINGBOT_CORS_ORIGINS");
        }
        if (!ApiRequestUtils.isBlank(origins)) {
            String[] parts = origins.split(",");
            List<String> cleaned = new ArrayList<String>();
            for (String part : parts) {
                if (!ApiRequestUtils.isBlank(part)) {
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

    private static int updateBacktestDataErrorStatus(String result) {
        String normalized = ApiRequestUtils.valueOrDefault(result, "").toLowerCase();
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
        String safe = ApiRequestUtils.valueOrDefault(message, fallback);
        return safe.length() > 500 ? safe.substring(0, 500) + "..." : safe;
    }

}
