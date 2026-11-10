package com.haichutieu.chatsystem.desktop.shared.ui;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/** Thin, testable wrapper around a UTF-8 property bundle. */
public final class I18n {
    private final Locale locale;
    private final ResourceBundle bundle;

    public I18n(String baseName, Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
        this.bundle = ResourceBundle.getBundle(Objects.requireNonNull(baseName, "baseName"), locale);
    }

    /** Resolves the locale once for a desktop app; -Dchat.locale=vi overrides the OS locale. */
    public static I18n forApplication(String baseName) {
        String override = System.getProperty("chat.locale");
        Locale locale = override == null || override.isBlank()
                ? Locale.getDefault()
                : Locale.forLanguageTag(override);
        return new I18n(baseName, locale);
    }

    public static I18n forUserApplication() {
        return forApplication("com.haichutieu.chatsystem.desktop.user.i18n.messages");
    }

    public Locale locale() {
        return locale;
    }

    public ResourceBundle bundle() {
        return bundle;
    }

    public String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException exception) {
            return key;
        }
    }

    public String format(String key, Object... arguments) {
        return MessageFormat.format(get(key), arguments);
    }
}
