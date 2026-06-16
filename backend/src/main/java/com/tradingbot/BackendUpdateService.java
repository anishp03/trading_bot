package com.tradingbot;

import io.javalin.http.Context;
import java.io.File;
import java.io.IOException;

final class BackendUpdateService {
    private BackendUpdateService() {
    }

    static String statusJson() {
        File script = new File(backendUpdateScriptPath()).getAbsoluteFile();
        File logFile = new File(backendUpdateLogPath()).getAbsoluteFile();
        return "{"
            + "\"enabled\":" + backendUpdateEnabled() + ","
            + "\"scriptPath\":" + ApiRequestUtils.jsonString(script.getAbsolutePath()) + ","
            + "\"scriptFound\":" + script.isFile() + ","
            + "\"logPath\":" + ApiRequestUtils.jsonString(logFile.getAbsolutePath())
            + "}";
    }

    static void triggerUpdate(Context ctx) {
        if (!backendUpdateEnabled()) {
            ctx.status(403).contentType("application/json").result("{"
                + "\"success\":false,"
                + "\"message\":\"Backend update is disabled. Set TRADINGBOT_ENABLE_BACKEND_UPDATE=true in live_backend/.env.\""
                + "}");
            return;
        }

        File script = new File(backendUpdateScriptPath()).getAbsoluteFile();
        if (!script.isFile()) {
            ctx.status(500).contentType("application/json").result("{"
                + "\"success\":false,"
                + "\"message\":" + ApiRequestUtils.jsonString("Update script was not found at " + script.getAbsolutePath())
                + "}");
            return;
        }

        File logFile = new File(backendUpdateLogPath()).getAbsoluteFile();
        File logDir = logFile.getParentFile();
        if (logDir != null) {
            logDir.mkdirs();
        }

        String runUpdate = "exec /bin/bash " + shellQuote(script.getAbsolutePath())
            + " --from-ui >> " + shellQuote(logFile.getAbsolutePath())
            + " 2>&1 < /dev/null";
        String command = "if command -v launchctl >/dev/null 2>&1; then "
            + "launchctl submit -l com.tradingbot.backend.update -- /bin/bash -lc " + shellQuote(runUpdate)
            + "; else nohup /bin/bash -lc " + shellQuote(runUpdate) + " & fi";

        try {
            new ProcessBuilder("/bin/bash", "-lc", command).start();
            ctx.status(202).contentType("application/json").result("{"
                + "\"success\":true,"
                + "\"message\":\"Backend update started. The API may disconnect while the live backend restarts.\","
                + "\"logPath\":" + ApiRequestUtils.jsonString(logFile.getAbsolutePath())
                + "}");
        } catch (IOException e) {
            ctx.status(500).contentType("application/json").result("{"
                + "\"success\":false,"
                + "\"message\":" + ApiRequestUtils.jsonString("Failed to start backend update: " + e.getMessage())
                + "}");
        }
    }

    private static boolean backendUpdateEnabled() {
        String configured = System.getProperty("tradingbot.enableBackendUpdate");
        if (ApiRequestUtils.isBlank(configured)) {
            configured = System.getenv("TRADINGBOT_ENABLE_BACKEND_UPDATE");
        }
        return Boolean.parseBoolean(ApiRequestUtils.valueOrDefault(configured, "false"));
    }

    private static String backendUpdateScriptPath() {
        String scriptPath = System.getProperty("tradingbot.backendUpdateScript");
        if (ApiRequestUtils.isBlank(scriptPath)) {
            scriptPath = System.getenv("TRADINGBOT_BACKEND_UPDATE_SCRIPT");
        }
        return ApiRequestUtils.valueOrDefault(
            scriptPath,
            "/Users/anishpatel/Documents/SoftwareProject/trading_bot/scripts/update-live-backend.sh"
        );
    }

    private static String backendUpdateLogPath() {
        String logPath = System.getProperty("tradingbot.backendUpdateLog");
        if (ApiRequestUtils.isBlank(logPath)) {
            logPath = System.getenv("TRADINGBOT_BACKEND_UPDATE_LOG");
        }
        return ApiRequestUtils.valueOrDefault(
            logPath,
            "/Users/anishpatel/Documents/SoftwareProject/live_backend/logs/update-backend.log"
        );
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
