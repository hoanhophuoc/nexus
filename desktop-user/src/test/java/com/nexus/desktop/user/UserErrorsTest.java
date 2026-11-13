package com.nexus.desktop.user;

import com.nexus.desktop.shared.mls.MlsException;
import com.nexus.desktop.shared.ui.I18n;
import com.nexus.sdk.ApiException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two error mappers decide what a person reads when a request fails: a stale session, a throttled
 * login and a dead server must not all look like "something went wrong". They are pure functions, so
 * every branch is checked here rather than through a screen.
 */
class UserErrorsTest {

    private static final String BASE = "com.nexus.desktop.user.i18n.messages";
    private final I18n i18n = new I18n(BASE, Locale.ENGLISH);

    @Test
    void loginFailuresNameTheCause() {
        assertThat(UserErrors.loginMessage(api(401), i18n)).isEqualTo(i18n.get("error.invalidCredentials"));
        assertThat(UserErrors.loginMessage(api(429), i18n)).isEqualTo(i18n.get("error.tooManyRequests"));
        assertThat(UserErrors.loginMessage(api(503), i18n)).isEqualTo(i18n.get("error.server"));
        assertThat(UserErrors.loginMessage(new IOException("offline"), i18n))
                .isEqualTo(i18n.get("error.unavailable"));
        assertThat(UserErrors.loginMessage(new IllegalStateException("unexpected"), i18n))
                .isEqualTo(i18n.get("error.generic"));
    }

    @Test
    void statusFailuresAddSessionExpiryAndNotFound() {
        assertThat(UserErrors.statusMessage(api(401), i18n)).isEqualTo(i18n.get("error.sessionExpired"));
        assertThat(UserErrors.statusMessage(api(404), i18n)).isEqualTo(i18n.get("error.notFound"));
        assertThat(UserErrors.statusMessage(api(429), i18n)).isEqualTo(i18n.get("error.tooManyRequests"));
        assertThat(UserErrors.statusMessage(api(500), i18n)).isEqualTo(i18n.get("error.server"));
        assertThat(UserErrors.statusMessage(new IOException("offline"), i18n))
                .isEqualTo(i18n.get("error.unavailable"));
        assertThat(UserErrors.statusMessage(new IllegalStateException("unexpected"), i18n))
                .isEqualTo(i18n.get("error.generic"));
    }

    @Test
    void anMlsMessageIsShownAsWritten() {
        assertThat(UserErrors.statusMessage(new MlsException("The MLS epoch is stale."), i18n))
                .as("protocol text is already user-facing")
                .isEqualTo("The MLS epoch is stale.");
        assertThat(UserErrors.statusMessage(new MlsException((String) null), i18n))
                .as("a protocol failure without text still has to say something")
                .isEqualTo(i18n.get("error.generic"));
    }

    private static ApiException api(int code) {
        return new ApiException("request failed") {
            @Override
            public int getCode() {
                return code;
            }
        };
    }
}
