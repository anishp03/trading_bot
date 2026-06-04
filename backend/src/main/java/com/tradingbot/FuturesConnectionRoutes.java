package com.tradingbot;

import io.javalin.Javalin;
import java.time.LocalDate;
import java.util.function.Function;

final class FuturesConnectionRoutes {
    private FuturesConnectionRoutes() {
    }

    static void register(
        Javalin app,
        String defaultPortfolioSymbols,
        Function<String, Integer> updateBacktestDataErrorStatus
    ) {
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
                ApiRequestUtils.parseBooleanOrDefault(ApiRequestUtils.requestParam(ctx, "enabled"), true),
                ApiRequestUtils.requestParam(ctx, "baseUrl"),
                ApiRequestUtils.requestParam(ctx, "environment"),
                ApiRequestUtils.requestParam(ctx, "username"),
                ApiRequestUtils.requestParam(ctx, "apiKey"),
                ApiRequestUtils.requestParam(ctx, "password"),
                ApiRequestUtils.requestParam(ctx, "secret"),
                ApiRequestUtils.requestParam(ctx, "appId"),
                ApiRequestUtils.requestParam(ctx, "appVersion"),
                ApiRequestUtils.requestParam(ctx, "cid"),
                ApiRequestUtils.requestParam(ctx, "accountId"),
                ApiRequestUtils.requestParam(ctx, "accountSpec"),
                ApiRequestUtils.requestParam(ctx, "dataset"),
                ApiRequestUtils.requestParam(ctx, "schema"),
                ApiRequestUtils.requestParam(ctx, "symbols"),
                ApiRequestUtils.requestParam(ctx, "marketHubUrl"),
                ApiRequestUtils.requestParam(ctx, "userHubUrl")
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

        app.post("/api/futures/market-data/update-backtest-data", ctx -> {
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols + ",GC");
            String startDate = ApiRequestUtils.valueOrDefault(ctx.queryParam("startDate"), LocalDate.now().minusYears(1).toString());
            String endDate = ApiRequestUtils.valueOrDefault(ctx.queryParam("endDate"), LocalDate.now().toString());
            String schema = ApiRequestUtils.valueOrDefault(ctx.queryParam("schema"), "ohlcv-1m");
            String result = FuturesConnectionManager.updateBacktestData(symbols, startDate, endDate, schema);
            if (result.contains("\"success\":false")) {
                ctx.status(updateBacktestDataErrorStatus.apply(result));
            }
            ctx.contentType("application/json").result(result);
        });

        app.post("/api/futures/market-data/topstepx/import", ctx -> {
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols + ",GC");
            String startDate = ApiRequestUtils.valueOrDefault(ctx.queryParam("startDate"), LocalDate.now().minusYears(1).toString());
            String endDate = ApiRequestUtils.valueOrDefault(ctx.queryParam("endDate"), LocalDate.now().toString());
            int maxContractsPerSymbol = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxContractsPerSymbol"), 1);
            ctx.contentType("application/json").result(FuturesConnectionManager.importTopstepxBars(symbols, startDate, endDate, maxContractsPerSymbol));
        });

        app.post("/api/futures/market-data/rebuild-derived", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            ctx.contentType("application/json").result(FuturesConnectionManager.rebuildDerivedFuturesData(symbol));
        });
    }
}
