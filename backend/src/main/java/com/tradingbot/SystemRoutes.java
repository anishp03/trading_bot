package com.tradingbot;

import io.javalin.Javalin;

final class SystemRoutes {
    private SystemRoutes() {
    }

    static void register(
        Javalin app,
        String appVersion,
        String buildId,
        long startedAtMs
    ) {
        app.get("/api/system/version", ctx -> {
            ctx.contentType("application/json").result("{"
                + "\"app\":\"trading_bot\","
                + "\"version\":" + ApiRequestUtils.jsonString(appVersion) + ","
                + "\"build\":" + ApiRequestUtils.jsonString(buildId)
                + "}");
        });

        app.get("/api/system/health", ctx -> {
            ctx.contentType("application/json").result("{"
                + "\"ok\":true,"
                + "\"uptimeSeconds\":" + Math.max(0L, (System.currentTimeMillis() - startedAtMs) / 1000L) + ","
                + "\"version\":" + ApiRequestUtils.jsonString(appVersion)
                + "}");
        });

        app.get("/api/system/backend-update", ctx -> {
            ctx.contentType("application/json").result(BackendUpdateService.statusJson());
        });

        app.post("/api/system/backend-update", BackendUpdateService::triggerUpdate);
    }
}
