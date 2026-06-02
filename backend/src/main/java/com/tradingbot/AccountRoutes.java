package com.tradingbot;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.function.BiFunction;

final class AccountRoutes {
    private AccountRoutes() {
    }

    static void register(
        Javalin app,
        AccountManager accountManager,
        BiFunction<Context, String, String> resolveAccountEmail
    ) {
        app.post("/api/account/register", ctx -> {
            ctx.status(403).result("Account creation is disabled.");
        });

        app.post("/api/account/change-password", ctx -> {
            String email = resolveAccountEmail.apply(ctx, ApiRequestUtils.requestParam(ctx, "email"));
            String currentPassword = ApiRequestUtils.requestParam(ctx, "currentPassword");
            String newPassword = ApiRequestUtils.requestParam(ctx, "newPassword");

            if (ApiRequestUtils.isBlank(currentPassword) || ApiRequestUtils.isBlank(newPassword)) {
                ctx.status(400).result("Missing password fields.");
                return;
            }

            boolean passwordChanged = accountManager.changePassword(email, currentPassword, newPassword);

            if (passwordChanged) {
                ctx.status(204);
            } else {
                ctx.status(400).result("Password update failed.");
            }
        });

        app.post("/api/account/details", ctx -> {
            String currentEmail = resolveAccountEmail.apply(ctx, ApiRequestUtils.requestParam(ctx, "currentEmail"));
            String name = ApiRequestUtils.requestParam(ctx, "name");
            String newEmail = resolveAccountEmail.apply(ctx, ApiRequestUtils.requestParam(ctx, "email"));
            String phoneNumber = ApiRequestUtils.requestParam(ctx, "phoneNumber");
            String address = ApiRequestUtils.requestParam(ctx, "address");

            if (ApiRequestUtils.isBlank(name) || ApiRequestUtils.isBlank(phoneNumber) || ApiRequestUtils.isBlank(address)) {
                ctx.status(400).result("Missing account detail fields.");
                return;
            }

            boolean updated = accountManager.updateAccountDetails(
                currentEmail.trim(),
                name.trim(),
                newEmail.trim(),
                phoneNumber.trim(),
                address.trim()
            );

            if (updated) {
                ctx.status(204);
            } else {
                ctx.status(400).result("Failed to update account details.");
            }
        });

        app.get("/api/settings/account", ctx -> {
            String email = resolveAccountEmail.apply(ctx, ctx.queryParam("email"));

            String accountName = accountManager.getAccountName(email);
            String phoneNumber = accountManager.getPhoneNumber(email);
            String address = accountManager.getAddress(email);

            ctx.contentType("application/json").result("{"
                + "\"name\":" + ApiRequestUtils.jsonString(accountName) + ","
                + "\"email\":" + ApiRequestUtils.jsonString(email) + ","
                + "\"phoneNumber\":" + ApiRequestUtils.jsonString(phoneNumber) + ","
                + "\"address\":" + ApiRequestUtils.jsonString(address)
                + "}");
        });
    }
}
