package com.tradingbot;

import io.javalin.Javalin;

final class FuturesMarketRoutes {
    private FuturesMarketRoutes() {
    }

    static void register(Javalin app) {
        app.get("/api/futures/instruments", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getInstrumentJson());
        });

        app.get("/api/futures/market-data", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getMarketDataStatusJson());
        });

        app.get("/api/futures/trade-analysis", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getTradeAnalysisJson(
                ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("strategyCode"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("strategyName"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("side"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("openedAt"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("closedAt"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("entryPrice"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("exitPrice"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("stopPrice"), ""),
                ApiRequestUtils.valueOrDefault(ctx.queryParam("targetPrice"), "")
            ));
        });
    }
}
