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
    }
}
