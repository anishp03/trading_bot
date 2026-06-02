package com.tradingbot;

import io.javalin.Javalin;
import io.javalin.http.Context;

final class LegacyEquityRoutes {
    private LegacyEquityRoutes() {
    }

    static void register(Javalin app) {
        app.get("/api/balance", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.post("/api/trade", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.get("/api/live-bot/status", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.post("/api/live-bot/start", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.post("/api/live-bot/stop", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.get("/api/settings/broker", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.post("/api/settings/broker", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.get("/api/backtests/market-data", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.get("/api/strategy", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.post("/api/strategy", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.post("/api/backtests/market-data/refresh", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.post("/api/backtests/generate", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.post("/api/backtests/clear", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.get("/api/backtests", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.get("/api/backtests/{id}/trades", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
        app.get("/api/backtests/{id}/trades/{tradeId}/chart", LegacyEquityRoutes::legacyEquityWorkflowRemoved);
    }

    private static void legacyEquityWorkflowRemoved(Context ctx) {
        ctx.status(410).contentType("application/json").result("{"
            + "\"success\":false,"
            + "\"errorCode\":\"LEGACY_EQUITY_WORKFLOW_REMOVED\","
            + "\"message\":\"Legacy Alpaca/equity workflow has been removed. Use the futures endpoints under /api/futures.\""
            + "}");
    }
}
