package com.tradingbot;

import io.javalin.Javalin;

final class FuturesRiskRoutes {
    private FuturesRiskRoutes() {
    }

    static void register(Javalin app) {
        app.get("/api/futures/risk", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            ctx.contentType("application/json").result(FuturesManager.getFuturesRiskSettingsJson(symbol));
        });

        app.get("/api/futures/funded-rule-profiles", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getFundedRuleProfilesJson());
        });

        app.post("/api/futures/risk", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            FuturesManager.FuturesRiskSettings settings = FuturesManager.loadFuturesRiskSettings(symbol);
            settings.accountSize = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("accountSize"), settings.accountSize);
            settings.maxTrailingDrawdown = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), settings.maxTrailingDrawdown);
            settings.dailyLossLimit = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), settings.dailyLossLimit);
            settings.maxRiskPerTrade = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), settings.maxRiskPerTrade);
            settings.maxContracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxContracts"), settings.maxContracts);
            settings.commissionPerContract = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), settings.commissionPerContract);
            settings.slippageTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("slippageTicks"), settings.slippageTicks);
            settings.profitTarget = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("profitTarget"), settings.profitTarget);

            if (FuturesManager.saveFuturesRiskSettings(symbol, settings)) {
                ctx.contentType("application/json").result(FuturesManager.getFuturesRiskSettingsJson(symbol));
            } else {
                ctx.status(500).contentType("application/json").result("{\"message\":\"Failed to save futures risk settings.\"}");
            }
        });
    }
}
