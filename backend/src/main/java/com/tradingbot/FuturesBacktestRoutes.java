package com.tradingbot;

import io.javalin.Javalin;

final class FuturesBacktestRoutes {
    private FuturesBacktestRoutes() {
    }

    static void register(Javalin app, String defaultPortfolioSymbols) {
        app.post("/api/futures/backtests/generate", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String startDate = ctx.queryParam("startDate");
            String endDate = ctx.queryParam("endDate");
            String fundedProfile = ApiRequestUtils.valueOrDefault(ctx.queryParam("fundedProfile"), "TOPSTEP_150K");
            FuturesManager.FuturesRiskSettings savedRisk = FuturesManager.loadFuturesRiskSettings(symbol);
            double accountSize = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("accountSize"), savedRisk.accountSize);
            double maxTrailingDrawdown = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), savedRisk.maxTrailingDrawdown);
            double dailyLossLimit = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), savedRisk.dailyLossLimit);
            double maxRiskPerTrade = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), savedRisk.maxRiskPerTrade);
            int maxContracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxContracts"), savedRisk.maxContracts);
            double commissionPerContract = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), savedRisk.commissionPerContract);
            double slippageTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("slippageTicks"), savedRisk.slippageTicks);
            double profitTarget = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("profitTarget"), savedRisk.profitTarget);

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
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols);
            String startDate = ctx.queryParam("startDate");
            String endDate = ctx.queryParam("endDate");
            int sourcePortfolioBacktestId = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("sourcePortfolioBacktestId"), 0);
            String firstSymbol = symbols.split(",")[0];
            FuturesManager.FuturesRiskSettings savedRisk = FuturesManager.loadFuturesRiskSettings(firstSymbol);
            double accountSize = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("accountSize"), savedRisk.accountSize);
            double maxTrailingDrawdown = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), savedRisk.maxTrailingDrawdown);
            double dailyLossLimit = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), savedRisk.dailyLossLimit);
            double maxRiskPerTrade = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), savedRisk.maxRiskPerTrade);
            int maxContracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxContracts"), savedRisk.maxContracts);
            double commissionPerContract = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), savedRisk.commissionPerContract);
            double slippageTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("slippageTicks"), savedRisk.slippageTicks);
            int maxOpenPositions = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxOpenPositions"), 1);
            int maxAggregateContracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxAggregateContracts"), maxContracts * Math.max(1, symbols.split(",").length));
            double maxAggregateMiniUnits = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxAggregateMiniUnits"), 0.0);
            boolean useSavedRisk = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("useSavedRisk"), true);
            double profitTarget = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("profitTarget"), savedRisk.profitTarget);
            String fundedProfile = ApiRequestUtils.valueOrDefault(ctx.queryParam("fundedProfile"), "TOPSTEP_150K");
            boolean continueAfterRuleViolation = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("continueAfterRuleViolation"), false);
            boolean qualitativeRiskEnabled = true;
            boolean dtmEnabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("dtmEnabled"), true);
            String strategyPreset = ApiRequestUtils.valueOrDefault(ctx.queryParam("strategyPreset"), "bestbiasfree");
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
                continueAfterRuleViolation,
                qualitativeRiskEnabled,
                dtmEnabled
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
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 0);
            int offset = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("offset"), 0);
            String sort = ApiRequestUtils.valueOrDefault(ctx.queryParam("sort"), "");
            if (ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("paged"), false)) {
                ctx.contentType("application/json").result(FuturesManager.getPortfolioBacktestTradesPageJson(
                    backtestId,
                    limit,
                    offset,
                    sort,
                    ApiRequestUtils.valueOrDefault(ctx.queryParam("outcome"), ""),
                    ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), ""),
                    ApiRequestUtils.valueOrDefault(ctx.queryParam("side"), ""),
                    ApiRequestUtils.valueOrDefault(ctx.queryParam("strategy"), ""),
                    ApiRequestUtils.valueOrDefault(ctx.queryParam("startDate"), ""),
                    ApiRequestUtils.valueOrDefault(ctx.queryParam("endDate"), "")
                ));
                return;
            }
            ctx.contentType("application/json").result(FuturesManager.getPortfolioBacktestTradesJson(backtestId, limit, sort));
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
    }
}
