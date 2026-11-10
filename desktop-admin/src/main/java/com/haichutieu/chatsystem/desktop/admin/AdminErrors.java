package com.haichutieu.chatsystem.desktop.admin;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.sdk.ApiException;

import java.io.IOException;

/** Maps admin transport failures to short, localized messages. */
final class AdminErrors {
    private AdminErrors() {
    }

    static String loginMessage(Exception exception, I18n i18n) {
        if (exception instanceof ApiException api) {
            return switch (api.getCode()) {
                case 401 -> i18n.get("admin.error.invalidCredentials");
                case 403 -> i18n.get("admin.error.noAccess");
                case 429 -> i18n.get("admin.error.tooManyRequests");
                default -> api.getCode() >= 500
                        ? i18n.get("admin.error.server") : i18n.get("admin.error.generic");
            };
        }
        if (exception instanceof IOException) return i18n.get("admin.error.unavailable");
        return i18n.get("admin.error.generic");
    }

    static String message(Exception exception, I18n i18n) {
        if (exception instanceof ApiException api) {
            return switch (api.getCode()) {
                case 401 -> i18n.get("admin.error.sessionExpired");
                case 403 -> i18n.get("admin.error.noAccess");
                case 404 -> i18n.get("admin.error.notFound");
                case 409 -> i18n.get("admin.error.conflict");
                case 429 -> i18n.get("admin.error.tooManyRequests");
                default -> api.getCode() >= 500
                        ? i18n.get("admin.error.server") : i18n.get("admin.error.generic");
            };
        }
        if (exception instanceof IOException) return i18n.get("admin.error.unavailable");
        return i18n.get("admin.error.generic");
    }
}
