package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.mls.MlsException;
import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import com.haichutieu.chatsystem.sdk.ApiException;

import java.io.IOException;

/** Maps transport/protocol failures to short, actionable user-facing messages. */
final class UserErrors {
    private UserErrors() {
    }

    static String loginMessage(Exception exception, I18n i18n) {
        if (exception instanceof ApiException api) {
            if (api.getCode() == 401) return i18n.get("error.invalidCredentials");
            if (api.getCode() == 429) return i18n.get("error.tooManyRequests");
            if (api.getCode() >= 500) return i18n.get("error.server");
        }
        if (exception instanceof IOException) return i18n.get("error.unavailable");
        return i18n.get("error.generic");
    }

    static String statusMessage(Exception exception, I18n i18n) {
        if (exception instanceof ApiException api) {
            if (api.getCode() == 401) return i18n.get("error.sessionExpired");
            if (api.getCode() == 404) return i18n.get("error.notFound");
            if (api.getCode() == 429) return i18n.get("error.tooManyRequests");
            if (api.getCode() >= 500) return i18n.get("error.server");
        }
        if (exception instanceof MlsException && exception.getMessage() != null) {
            return exception.getMessage();
        }
        if (exception instanceof IOException) return i18n.get("error.unavailable");
        return i18n.get("error.generic");
    }
}
