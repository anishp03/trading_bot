package com.tradingbot;

import io.javalin.Javalin;

final class FuturesLiveRoutes {
    private FuturesLiveRoutes() {
    }

    static void register(Javalin app, String defaultPortfolioSymbols) {
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
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String side = ApiRequestUtils.valueOrDefault(ctx.queryParam("side"), "LONG");
            int contracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("contracts"), 1);
            double entryPrice = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("entryPrice"), 0.0);
            double stopPrice = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("stopPrice"), 0.0);
            double targetPrice = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("targetPrice"), 0.0);
            String reason = ApiRequestUtils.valueOrDefault(ctx.queryParam("reason"), "manual dry-run");
            ctx.contentType("application/json").result(FuturesManager.dryRunTopstepxOrder(symbol, side, contracts, entryPrice, stopPrice, targetPrice, reason));
        });

        app.post("/api/futures/live/topstepx/order-submit", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String side = ApiRequestUtils.valueOrDefault(ctx.queryParam("side"), "LONG");
            int contracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("contracts"), 1);
            double entryPrice = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("entryPrice"), 0.0);
            double stopPrice = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("stopPrice"), 0.0);
            double targetPrice = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("targetPrice"), 0.0);
            String reason = ApiRequestUtils.valueOrDefault(ctx.queryParam("reason"), "live strategy order");
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
            int sessionId = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("sessionId"), 0);
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 100);
            String accountId = ApiRequestUtils.valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveSignalDecisionsJson(sessionId, limit, accountId));
        });

        app.get("/api/futures/live/cycle-audit", ctx -> {
            int sessionId = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("sessionId"), 0);
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 100);
            ctx.contentType("application/json").result(FuturesManager.getLiveCycleAuditJson(sessionId, limit));
        });

        app.get("/api/futures/live/thinking", ctx -> {
            int sessionId = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("sessionId"), 0);
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 100);
            ctx.contentType("application/json").result(FuturesManager.getLiveThinkingJson(sessionId, limit));
        });

        app.post("/api/futures/live/thinking/clear", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.clearLiveThinkingLogJson());
        });

        app.get("/api/futures/live/metrics", ctx -> {
            String accountId = ApiRequestUtils.valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveMetricsJson(accountId));
        });

        app.get("/api/futures/live/marks", ctx -> {
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols);
            String timeframe = ApiRequestUtils.valueOrDefault(ctx.queryParam("timeframe"), "1m");
            String accountId = ApiRequestUtils.valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveMarksJson(symbols, timeframe, accountId));
        });

        app.get("/api/futures/live/chart", ctx -> {
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols);
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 240);
            ctx.contentType("application/json").result(FuturesManager.getLiveChartJson(symbols, limit));
        });

        app.get("/api/futures/live/monitor", ctx -> {
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols);
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 180);
            String timeframe = ApiRequestUtils.valueOrDefault(ctx.queryParam("timeframe"), "1m");
            ctx.contentType("application/json").result(FuturesManager.getLiveMonitorJson(symbols, limit, timeframe));
        });

        app.get("/api/futures/live/orders", ctx -> {
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 50);
            String accountId = ApiRequestUtils.valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveOrderLedgerJson(limit, accountId));
        });

        app.get("/api/futures/live/trade-cache", ctx -> {
            String accountId = ApiRequestUtils.valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.getLiveTradeCacheJson(accountId));
        });

        app.post("/api/futures/live/trade-cache", ctx -> {
            String accountId = ApiRequestUtils.valueOrDefault(ctx.queryParam("accountId"), "");
            ctx.contentType("application/json").result(FuturesManager.saveLiveTradeCacheJson(accountId, ctx.body()));
        });

        app.get("/api/futures/live/risk-events", ctx -> {
            int sessionId = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("sessionId"), 0);
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 50);
            ctx.contentType("application/json").result(FuturesManager.getLiveRiskEventsJson(sessionId, limit));
        });

        app.get("/api/futures/live/realtime/status", ctx -> {
            ctx.contentType("application/json").result(ProjectXRealtimeManager.getStatusJson());
        });

        app.get("/api/futures/live/realtime/plan", ctx -> {
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols);
            boolean includeDepth = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("includeDepth"), false);
            ctx.contentType("application/json").result(ProjectXRealtimeManager.getPlanJson(symbols, includeDepth));
        });

        app.get("/api/futures/live/realtime/events", ctx -> {
            int limit = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("limit"), 100);
            ctx.contentType("application/json").result(ProjectXRealtimeManager.getEventsJson(limit));
        });

        app.post("/api/futures/live/realtime/start", ctx -> {
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols);
            boolean includeDepth = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("includeDepth"), false);
            boolean confirmed = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("confirmed"), false);
            ctx.contentType("application/json").result(ProjectXRealtimeManager.startReadOnly(symbols, includeDepth, confirmed));
        });

        app.post("/api/futures/live/realtime/stop", ctx -> {
            ctx.contentType("application/json").result(ProjectXRealtimeManager.stopReadOnly());
        });

        app.post("/api/futures/live/start", ctx -> {
            String symbol = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbol"), "MNQ");
            String symbols = ApiRequestUtils.valueOrDefault(ctx.queryParam("symbols"), defaultPortfolioSymbols);
            String executionMode = ApiRequestUtils.valueOrDefault(ctx.queryParam("executionMode"), "SIMULATED");
            String fundedProfile = ApiRequestUtils.valueOrDefault(ctx.queryParam("fundedProfile"), "TOPSTEP_50K");
            String accountId = ApiRequestUtils.valueOrDefault(ctx.queryParam("accountId"), "");
            String strategyPreset = ApiRequestUtils.valueOrDefault(ctx.queryParam("strategyPreset"), "bestbiasfree");
            FuturesManager.FuturesRiskSettings savedRisk = FuturesManager.loadFuturesRiskSettings(symbol);
            double accountSize = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("accountSize"), savedRisk.accountSize);
            double maxTrailingDrawdown = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxTrailingDrawdown"), savedRisk.maxTrailingDrawdown);
            double dailyLossLimit = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("dailyLossLimit"), savedRisk.dailyLossLimit);
            double maxRiskPerTrade = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxRiskPerTrade"), savedRisk.maxRiskPerTrade);
            int maxContracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxContracts"), savedRisk.maxContracts);
            double commissionPerContract = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("commissionPerContract"), savedRisk.commissionPerContract);
            double slippageTicks = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("slippageTicks"), savedRisk.slippageTicks);
            double profitTarget = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("profitTarget"), savedRisk.profitTarget);
            int maxOpenPositions = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxOpenPositions"), 1);
            int maxAggregateContracts = ApiRequestUtils.parseIntOrDefault(ctx.queryParam("maxAggregateContracts"), maxContracts);
            double maxAggregateMiniUnits = ApiRequestUtils.parseDoubleOrDefault(ctx.queryParam("maxAggregateMiniUnits"), 5.0);
            String riskSizingMode = ApiRequestUtils.valueOrDefault(ctx.queryParam("riskSizingMode"), "STATIC_WITHDRAW_DAILY");
            boolean dtmEnabled = ApiRequestUtils.parseBooleanOrDefault(ctx.queryParam("dtmEnabled"), true);

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
                riskSizingMode,
                dtmEnabled
            ));
        });

        app.post("/api/futures/live/stop", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.stopLive());
        });
    }
}
