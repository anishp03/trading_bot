package com.tradingbot;

import io.javalin.http.Context;

public final class ApiRequestUtils {
    private ApiRequestUtils() {
    }

    public static String requestParam(Context ctx, String name) {
        try {
            return ctx.formParam(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static double parseDoubleOrDefault(String value, double defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    public static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String jsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    public static String safeMessage(String message, String fallback) {
        if (message == null || message.trim().isEmpty()) {
            return fallback;
        }
        String trimmed = message.trim();
        if (trimmed.length() > 300) {
            return trimmed.substring(0, 300) + "...";
        }
        return trimmed;
    }
}
