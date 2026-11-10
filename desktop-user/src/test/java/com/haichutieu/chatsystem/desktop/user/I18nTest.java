package com.haichutieu.chatsystem.desktop.user;

import com.haichutieu.chatsystem.desktop.shared.ui.I18n;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class I18nTest {
    private static final String BASE = "com.haichutieu.chatsystem.desktop.user.i18n.messages";

    @Test
    void englishAndVietnameseBundlesExposeTheSameKeys() {
        I18n english = new I18n(BASE, Locale.ENGLISH);
        I18n vietnamese = new I18n(BASE, Locale.forLanguageTag("vi"));

        Set<String> englishKeys = english.bundle().keySet();
        Set<String> vietnameseKeys = vietnamese.bundle().keySet();

        assertThat(vietnameseKeys).isEqualTo(englishKeys);
    }

    @Test
    void vietnameseTranslatesContentButKeepsAutomationLabelsStable() {
        I18n vietnamese = new I18n(BASE, Locale.forLanguageTag("vi"));

        assertThat(vietnamese.get("login.title")).isEqualTo("Chào mừng trở lại");
        assertThat(vietnamese.get("shell.noMessages")).isEqualTo("Chưa có tin nhắn");
        assertThat(vietnamese.get("login.submit")).isEqualTo("Sign in");
        assertThat(vietnamese.get("login.usernamePrompt")).isEqualTo("Username");
        assertThat(vietnamese.get("login.passwordPrompt")).isEqualTo("Password");
        assertThat(vietnamese.get("shell.composePrompt")).isEqualTo("Write a message");
        assertThat(vietnamese.get("shell.send")).isEqualTo("Send");
    }

    @Test
    void missingKeyFallsBackToTheKeyInsteadOfThrowing() {
        I18n english = new I18n(BASE, Locale.ENGLISH);

        assertThat(english.get("missing.key")).isEqualTo("missing.key");
    }

    @Test
    void everyKeyReferencedByTheFxmlViewsExists() throws Exception {
        I18n english = new I18n(BASE, Locale.ENGLISH);
        for (String view : new String[] {"login.fxml", "shell.fxml", "user-picker.fxml"}) {
            String fxml;
            try (var input = I18nTest.class.getResourceAsStream("/com/haichutieu/chatsystem/desktop/user/view/" + view)) {
                assertThat(input).as(view).isNotNull();
                fxml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            var matcher = java.util.regex.Pattern.compile("%([A-Za-z0-9_.]+)").matcher(fxml);
            while (matcher.find()) {
                String key = matcher.group(1);
                assertThat(english.get(key)).as(view + " key " + key).isNotEqualTo(key);
            }
        }
    }
}
