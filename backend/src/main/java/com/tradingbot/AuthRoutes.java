package com.tradingbot;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class AuthRoutes {
    private AuthRoutes() {
    }

    static void register(
        Javalin app,
        AccountManager accountManager,
        BiFunction<Context, String, String> loginThrottleKey,
        Predicate<String> isLoginLocked,
        Consumer<String> recordFailedLogin,
        Consumer<String> clearLoginFailures,
        Function<AccountManager.AccountSession, String> sessionJson,
        Function<Context, String> authenticatedEmail,
        Function<Context, String> authenticatedRole,
        Function<Context, String> authenticatedExpiresAt,
        Function<Context, String> extractBearerToken
    ) {
        app.post("/api/login", ctx -> {
            String email = ApiRequestUtils.requestParam(ctx, "email");
            String password = ApiRequestUtils.requestParam(ctx, "password");
            String loginKey = loginThrottleKey.apply(ctx, email);

            if (isLoginLocked.test(loginKey)) {
                ctx.status(429).result("Too many failed login attempts. Try again later.");
                return;
            }

            AccountManager.AccountSession session = accountManager.createSession(email, password);

            if (session != null) {
                clearLoginFailures.accept(loginKey);
                ctx.contentType("application/json").result(sessionJson.apply(session));
            } else {
                recordFailedLogin.accept(loginKey);
                ctx.status(401).result("Invalid credentials.");
            }
        });

        app.get("/api/session", ctx -> {
            ctx.contentType("application/json").result("{"
                + "\"authenticated\":true,"
                + "\"email\":" + ApiRequestUtils.jsonString(authenticatedEmail.apply(ctx)) + ","
                + "\"role\":" + ApiRequestUtils.jsonString(authenticatedRole.apply(ctx)) + ","
                + "\"expiresAt\":" + ApiRequestUtils.jsonString(authenticatedExpiresAt.apply(ctx))
                + "}");
        });

        app.post("/api/logout", ctx -> {
            accountManager.revokeSession(extractBearerToken.apply(ctx));
            ctx.status(204);
        });
    }
}
