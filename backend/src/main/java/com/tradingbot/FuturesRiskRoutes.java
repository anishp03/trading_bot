package com.tradingbot;

import io.javalin.Javalin;

final class FuturesRiskRoutes {
    private FuturesRiskRoutes() {
    }

    static void register(Javalin app) {
        app.get("/api/futures/risk", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String preset = ctx.queryParam("preset");
            String slot = preset == null ? ApiRequestUtils.valueOrDefault(ctx.queryParam("slot"), "BACKTEST") : FuturesManager.strategyPresetSlot(preset);
            ctx.contentType("application/json").result(FuturesManager.getFuturesRiskSettingsJson(symbol, slot));
        });

        app.get("/api/futures/funded-rule-profiles", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.getFundedRuleProfilesJson());
        });

        app.post("/api/futures/risk", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String preset = ctx.queryParam("preset");
            String slot = preset == null ? ApiRequestUtils.valueOrDefault(ctx.queryParam("slot"), "BACKTEST") : FuturesManager.strategyPresetSlot(preset);
            if ("LIVE".equalsIgnoreCase(slot)) {
                ctx.status(400).contentType("application/json").result("{\"message\":\"Live Risk Config slot is legacy read-only. Save a named strategy preset risk config instead.\"}");
                return;
            }
            FuturesManager.FuturesRiskSettings settings = FuturesManager.loadFuturesRiskSettings(symbol, slot);
            settings.accountSize = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("accountSize"), settings.accountSize);
            settings.maxTrailingDrawdown = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), settings.maxTrailingDrawdown);
            settings.dailyLossLimit = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), settings.dailyLossLimit);
            settings.maxRiskPerTrade = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), settings.maxRiskPerTrade);
            settings.maxContracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxContracts"), settings.maxContracts);
            settings.commissionPerContract = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), settings.commissionPerContract);
            settings.slippageTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("slippageTicks"), settings.slippageTicks);
            settings.profitTarget = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("profitTarget"), settings.profitTarget);
            settings.maxInitialRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxInitialRiskTicks"), settings.maxInitialRiskTicks);
            settings.orbCompressedMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("orbCompressedMaxRiskTicks"), settings.orbCompressedMaxRiskTicks);
            settings.orbRetestMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("orbRetestMaxRiskTicks"), settings.orbRetestMaxRiskTicks);
            settings.openingMomentumMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("openingMomentumMaxRiskTicks"), settings.openingMomentumMaxRiskTicks);
            settings.lateOrbContinuationMaxRiskTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("lateOrbContinuationMaxRiskTicks"), settings.lateOrbContinuationMaxRiskTicks);

            if (FuturesManager.saveFuturesRiskSettings(symbol, slot, settings)) {
                ctx.contentType("application/json").result(FuturesManager.getFuturesRiskSettingsJson(symbol, slot));
            } else {
                ctx.status(500).contentType("application/json").result("{\"message\":\"Failed to save futures risk settings.\"}");
            }
        });
    }
}
