package com.tradingbot;

import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ApiAuthSupport {
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_FAILURE_WINDOW_MS = 15L * 60L * 1000L;
    private static final long LOGIN_LOCKOUT_MS = 15L * 60L * 1000L;
    private static final Map<String, LoginAttempt> LOGIN_ATTEMPTS = new ConcurrentHashMap<String, LoginAttempt>();

    private ApiAuthSupport() {
    }

    static void authorizeApiRequest(
        Context ctx,
        AccountManager accountManager,
        boolean requireAppAuth,
        String primaryAccountEmail
    ) {
        String path = ctx.path();
        if (isPublicApiPath(path) || "OPTIONS".equalsIgnoreCase(ctx.method())) {
            return;
        }

        if (!requireAppAuth) {
            ctx.attribute("accountId", accountManager.getAccountId(primaryAccountEmail));
            ctx.attribute("accountEmail", primaryAccountEmail);
            ctx.attribute("accountRole", "admin");
            ctx.attribute("accountSessionExpiresAt", "");
            return;
        }

        AccountManager.AccountSession session = accountManager.getSession(extractBearerToken(ctx));
        if (session == null) {
            throw new UnauthorizedResponse("Authentication required.");
        }

        String requiredRole = requiredRoleFor(ctx.method(), path);
        if (!roleAllows(session.role, requiredRole)) {
            throw new ForbiddenResponse("Insufficient account role.");
        }

        ctx.attribute("accountId", session.accountId);
        ctx.attribute("accountEmail", session.email);
        ctx.attribute("accountRole", session.role);
        ctx.attribute("accountSessionExpiresAt", session.expiresAt);
    }

    static String sessionJson(AccountManager.AccountSession session) {
        return "{"
            + "\"token\":" + ApiRequestUtils.jsonString(session.token) + ","
            + "\"email\":" + ApiRequestUtils.jsonString(session.email) + ","
            + "\"role\":" + ApiRequestUtils.jsonString(session.role) + ","
            + "\"expiresAt\":" + ApiRequestUtils.jsonString(session.expiresAt)
            + "}";
    }

    static String extractBearerToken(Context ctx) {
        String header = ctx.header("Authorization");
        if (ApiRequestUtils.isBlank(header)) {
            return "";
        }

        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return "";
    }

    static String authenticatedEmail(Context ctx) {
        String email = ctx.attribute("accountEmail");
        return ApiRequestUtils.isBlank(email) ? MainServer.PRIMARY_ACCOUNT_EMAIL : email;
    }

    static String authenticatedRole(Context ctx) {
        String role = ctx.attribute("accountRole");
        return ApiRequestUtils.isBlank(role) ? "viewer" : role;
    }

    static String authenticatedExpiresAt(Context ctx) {
        String expiresAt = ctx.attribute("accountSessionExpiresAt");
        return ApiRequestUtils.isBlank(expiresAt) ? "" : expiresAt;
    }

    static String loginThrottleKey(Context ctx, String email) {
        String normalizedEmail = ApiRequestUtils.isBlank(email) ? "unknown" : email.trim().toLowerCase();
        String ip = ApiRequestUtils.valueOrDefault(ctx.ip(), "unknown");
        return ip + "|" + normalizedEmail;
    }

    static boolean isLoginLocked(String key) {
        LoginAttempt attempt = LOGIN_ATTEMPTS.get(key);
        if (attempt == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (attempt.lockedUntilMs > now) {
            return true;
        }
        if (attempt.lockedUntilMs > 0L || now - attempt.firstFailureMs > LOGIN_FAILURE_WINDOW_MS) {
            LOGIN_ATTEMPTS.remove(key);
        }
        return false;
    }

    static void recordFailedLogin(String key) {
        long now = System.currentTimeMillis();
        LOGIN_ATTEMPTS.compute(key, (ignored, attempt) -> {
            LoginAttempt next = attempt;
            if (next == null || now - next.firstFailureMs > LOGIN_FAILURE_WINDOW_MS) {
                next = new LoginAttempt();
                next.firstFailureMs = now;
            }
            next.failureCount++;
            if (next.failureCount >= MAX_LOGIN_FAILURES) {
                next.lockedUntilMs = now + LOGIN_LOCKOUT_MS;
            }
            return next;
        });
    }

    static void clearLoginFailures(String key) {
        LOGIN_ATTEMPTS.remove(key);
    }

    static String resolveAccountEmail(String email) {
        return ApiRequestUtils.isBlank(email) ? MainServer.PRIMARY_ACCOUNT_EMAIL : email.trim();
    }

    static String resolveAccountEmail(Context ctx, String email) {
        String authenticatedEmail = authenticatedEmail(ctx);
        String requestedEmail = ApiRequestUtils.isBlank(email) ? authenticatedEmail : email.trim();

        if (!requestedEmail.equalsIgnoreCase(authenticatedEmail) && !"admin".equals(authenticatedRole(ctx))) {
            throw new ForbiddenResponse("Cannot access another account.");
        }
        return requestedEmail;
    }

    private static boolean isPublicApiPath(String path) {
        return "/api/login".equals(path)
            || "/api/system/health".equals(path)
            || "/api/system/version".equals(path);
    }

    private static String requiredRoleFor(String method, String path) {
        if ("GET".equals(method)) {
            if ("/api/settings/broker".equals(path) || path.startsWith("/api/futures/connections")) {
                return "admin";
            }
            return "viewer";
        }

        if ("/api/logout".equals(path)
            || "/api/account/change-password".equals(path)
            || "/api/account/details".equals(path)) {
            return "viewer";
        }

        if (path.startsWith("/api/live-bot/")
            || path.startsWith("/api/futures/live/")
            || "/api/trade".equals(path)) {
            return "operator";
        }

        return "admin";
    }

    private static boolean roleAllows(String actualRole, String requiredRole) {
        String actual = ApiRequestUtils.isBlank(actualRole) ? "viewer" : actualRole.trim().toLowerCase();
        String required = ApiRequestUtils.isBlank(requiredRole) ? "viewer" : requiredRole.trim().toLowerCase();

        if ("admin".equals(actual)) {
            return true;
        }
        if ("operator".equals(actual)) {
            return "operator".equals(required) || "viewer".equals(required);
        }
        return "viewer".equals(required);
    }

    private static class LoginAttempt {
        int failureCount;
        long firstFailureMs;
        long lockedUntilMs;
    }
}
