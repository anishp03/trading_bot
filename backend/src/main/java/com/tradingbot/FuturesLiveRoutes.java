package com.tradingbot;

import io.javalin.Javalin;
import java.util.function.Function;

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
            FuturesManager.FuturesRiskSettings savedRisk = FuturesManager.loadFuturesRiskSettings(symbol);
            LiveStartRequest request = liveStartRequestFromParams(ctx::queryParam, defaultPortfolioSymbols, savedRisk);

            ctx.contentType("application/json").result(FuturesManager.startLive(
                request.symbol,
                request.executionMode,
                request.fundedProfile,
                request.accountId,
                request.accountSize,
                request.maxTrailingDrawdown,
                request.dailyLossLimit,
                request.maxRiskPerTrade,
                request.maxContracts,
                request.commissionPerContract,
                request.slippageTicks,
                request.profitTarget,
                request.maxOpenPositions,
                request.maxAggregateContracts,
                request.maxAggregateMiniUnits,
                request.symbols,
                request.strategyPreset,
                request.riskSizingMode,
                request.dtmEnabled
            ));
        });

        app.post("/api/futures/live/stop", ctx -> {
            ctx.contentType("application/json").result(FuturesManager.stopLive());
        });
    }

    static LiveStartRequest liveStartRequestFromParams(
        Function<String, String> params,
        String defaultPortfolioSymbols,
        FuturesManager.FuturesRiskSettings savedRisk
    ) {
        FuturesManager.FuturesRiskSettings risk = savedRisk == null ? new FuturesManager.FuturesRiskSettings() : savedRisk;
        String symbol = ApiRequestUtils.valueOrDefault(params.apply("symbol"), "MNQ");
        int maxContracts = ApiRequestUtils.parseIntOrDefault(params.apply("maxContracts"), risk.maxContracts);
        return new LiveStartRequest(
            symbol,
            ApiRequestUtils.valueOrDefault(params.apply("symbols"), defaultPortfolioSymbols),
            ApiRequestUtils.valueOrDefault(params.apply("executionMode"), "SIMULATED"),
            ApiRequestUtils.valueOrDefault(params.apply("fundedProfile"), "TOPSTEP_50K"),
            ApiRequestUtils.valueOrDefault(params.apply("accountId"), ""),
            ApiRequestUtils.valueOrDefault(params.apply("strategyPreset"), "bestbiasfree"),
            ApiRequestUtils.parseDoubleOrDefault(params.apply("accountSize"), risk.accountSize),
            ApiRequestUtils.parseDoubleOrDefault(params.apply("maxTrailingDrawdown"), risk.maxTrailingDrawdown),
            ApiRequestUtils.parseDoubleOrDefault(params.apply("dailyLossLimit"), risk.dailyLossLimit),
            ApiRequestUtils.parseDoubleOrDefault(params.apply("maxRiskPerTrade"), risk.maxRiskPerTrade),
            maxContracts,
            ApiRequestUtils.parseDoubleOrDefault(params.apply("commissionPerContract"), risk.commissionPerContract),
            ApiRequestUtils.parseDoubleOrDefault(params.apply("slippageTicks"), risk.slippageTicks),
            ApiRequestUtils.parseDoubleOrDefault(params.apply("profitTarget"), risk.profitTarget),
            ApiRequestUtils.parseIntOrDefault(params.apply("maxOpenPositions"), 1),
            ApiRequestUtils.parseIntOrDefault(params.apply("maxAggregateContracts"), maxContracts),
            ApiRequestUtils.parseDoubleOrDefault(params.apply("maxAggregateMiniUnits"), 5.0),
            ApiRequestUtils.valueOrDefault(params.apply("riskSizingMode"), "DYNAMIC_COMPOUND_MLL"),
            ApiRequestUtils.parseBooleanOrDefault(params.apply("dtmEnabled"), true)
        );
    }

    static final class LiveStartRequest {
        final String symbol;
        final String symbols;
        final String executionMode;
        final String fundedProfile;
        final String accountId;
        final String strategyPreset;
        final double accountSize;
        final double maxTrailingDrawdown;
        final double dailyLossLimit;
        final double maxRiskPerTrade;
        final int maxContracts;
        final double commissionPerContract;
        final double slippageTicks;
        final double profitTarget;
        final int maxOpenPositions;
        final int maxAggregateContracts;
        final double maxAggregateMiniUnits;
        final String riskSizingMode;
        final boolean dtmEnabled;

        LiveStartRequest(
            String symbol,
            String symbols,
            String executionMode,
            String fundedProfile,
            String accountId,
            String strategyPreset,
            double accountSize,
            double maxTrailingDrawdown,
            double dailyLossLimit,
            double maxRiskPerTrade,
            int maxContracts,
            double commissionPerContract,
            double slippageTicks,
            double profitTarget,
            int maxOpenPositions,
            int maxAggregateContracts,
            double maxAggregateMiniUnits,
            String riskSizingMode,
            boolean dtmEnabled
        ) {
            this.symbol = symbol;
            this.symbols = symbols;
            this.executionMode = executionMode;
            this.fundedProfile = fundedProfile;
            this.accountId = accountId;
            this.strategyPreset = strategyPreset;
            this.accountSize = accountSize;
            this.maxTrailingDrawdown = maxTrailingDrawdown;
            this.dailyLossLimit = dailyLossLimit;
            this.maxRiskPerTrade = maxRiskPerTrade;
            this.maxContracts = maxContracts;
            this.commissionPerContract = commissionPerContract;
            this.slippageTicks = slippageTicks;
            this.profitTarget = profitTarget;
            this.maxOpenPositions = maxOpenPositions;
            this.maxAggregateContracts = maxAggregateContracts;
            this.maxAggregateMiniUnits = maxAggregateMiniUnits;
            this.riskSizingMode = riskSizingMode;
            this.dtmEnabled = dtmEnabled;
        }
    }
}
