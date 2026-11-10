package com.haichutieu.chatsystem.desktop.shared.ui;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Applies the shared design tokens to a scene or dialog and remembers the user's choice.
 *
 * <p>The stylesheet uses semantic tokens only; screens never hard-code colours. The effective
 * theme follows the platform colour scheme when the preference is SYSTEM.</p>
 */
public final class ThemeManager {
    private static final String PREFERENCE_KEY = "theme";
    private static final String LIGHT_CLASS = "theme-light";
    private static final String DARK_CLASS = "theme-dark";
    private static final String STYLESHEET = Objects.requireNonNull(
            ThemeManager.class.getResource("theme.css"), "theme.css is missing").toExternalForm();

    /*
     * The UI font is bundled instead of resolved from the host so JavaFX golden
     * screenshots are reproducible on developer machines and CI.
     */
    static {
        loadFont("fonts/ChatSystemSans-Regular.otf");
        loadFont("fonts/ChatSystemSans-Bold.otf");
    }

    private final Preferences preferences = Preferences.userNodeForPackage(ThemeManager.class);
    private final ObjectProperty<Theme> theme = new SimpleObjectProperty<>(loadPreference());
    private final ObjectProperty<Theme> effectiveTheme = new SimpleObjectProperty<>(Theme.LIGHT);

    public ThemeManager() {
        theme.addListener((observable, previous, next) -> {
            savePreference(next);
            refreshEffectiveTheme();
        });
        try {
            Platform.getPreferences().colorSchemeProperty().addListener((observable, previous, next) -> {
                if (theme.get() == Theme.SYSTEM) refreshEffectiveTheme();
            });
        } catch (RuntimeException ignored) {
            // Unit tests and very early startup can run before the toolkit owns a Preferences map.
        }
        refreshEffectiveTheme();
    }

    private static void loadFont(String resource) {
        try (InputStream stream = ThemeManager.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing UI font resource: " + resource);
            }
            if (Font.loadFont(stream, 13) == null) {
                throw new IllegalStateException("Cannot load UI font: " + resource);
            }
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public static String stylesheetUrl() {
        return STYLESHEET;
    }

    public ObjectProperty<Theme> themeProperty() {
        return theme;
    }

    public ReadOnlyObjectProperty<Theme> effectiveThemeProperty() {
        return effectiveTheme;
    }

    public Theme theme() {
        return theme.get();
    }

    public Theme effectiveTheme() {
        return effectiveTheme.get();
    }

    public boolean isDark() {
        return effectiveTheme.get() == Theme.DARK;
    }

    public void setTheme(Theme next) {
        theme.set(Objects.requireNonNull(next, "theme"));
    }

    public void toggle() {
        setTheme(isDark() ? Theme.LIGHT : Theme.DARK);
    }

    /** Applies tokens and the theme class to a scene root. */
    public void apply(Scene scene) {
        if (scene == null) return;
        apply(scene.getRoot());
        if (!scene.getStylesheets().contains(STYLESHEET)) scene.getStylesheets().add(STYLESHEET);
    }

    /** Applies tokens to a dialog pane or any other detached root. */
    public void apply(Parent root) {
        if (root == null) return;
        if (!root.getStylesheets().contains(STYLESHEET)) root.getStylesheets().add(STYLESHEET);
        root.getStyleClass().removeAll(LIGHT_CLASS, DARK_CLASS);
        root.getStyleClass().add(isDark() ? DARK_CLASS : LIGHT_CLASS);
    }

    private void refreshEffectiveTheme() {
        Theme requested = theme.get();
        effectiveTheme.set(requested == Theme.SYSTEM ? systemTheme() : requested);
    }

    private static Theme systemTheme() {
        try {
            return Platform.getPreferences().getColorScheme() == ColorScheme.DARK ? Theme.DARK : Theme.LIGHT;
        } catch (RuntimeException ignored) {
            return Theme.LIGHT;
        }
    }

    private Theme loadPreference() {
        try {
            return Theme.valueOf(preferences.get(PREFERENCE_KEY, Theme.SYSTEM.name()));
        } catch (IllegalArgumentException | SecurityException exception) {
            return Theme.SYSTEM;
        }
    }

    private void savePreference(Theme next) {
        try {
            preferences.put(PREFERENCE_KEY, next.name());
        } catch (SecurityException ignored) {
            // A locked-down preferences store must not break the UI.
        }
    }
}
