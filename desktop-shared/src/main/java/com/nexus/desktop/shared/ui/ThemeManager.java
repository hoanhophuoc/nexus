package com.nexus.desktop.shared.ui;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.PopupControl;
import javafx.scene.text.Font;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.prefs.Preferences;

/**
 * Applies the shared design tokens to a scene or dialog and remembers the user's choice.
 *
 * <p>The stylesheet uses semantic tokens only; screens never hard-code colours. The effective
 * theme follows the platform colour scheme when the preference is SYSTEM.</p>
 *
 * <p>This is the one owner of the application theme: a scene handed to {@link #apply(Scene)}
 * belongs to the manager from then on, and so do the popup windows JavaFX opens for its controls
 * ({@link PopupThemes}). A theme change therefore reaches an open menu or date picker as well as
 * the window that opened it.</p>
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
        loadFont("fonts/nexusSans-Regular.otf");
        loadFont("fonts/nexusSans-Bold.otf");
    }

    private final Preferences preferences = Preferences.userNodeForPackage(ThemeManager.class);
    private final ObjectProperty<Theme> theme = new SimpleObjectProperty<>(loadPreference());
    private final ObjectProperty<Theme> effectiveTheme = new SimpleObjectProperty<>(Theme.LIGHT);
    /* Roots and windows this manager styled; weak so a closed screen is not retained. */
    private final Set<Parent> styledRoots = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Window> styledWindows = Collections.newSetFromMap(new WeakHashMap<>());

    public ThemeManager() {
        theme.addListener((observable, previous, next) -> {
            savePreference(next);
            refreshEffectiveTheme();
        });
        effectiveTheme.addListener((observable, previous, next) -> restyleOpenWindows());
        try {
            Platform.getPreferences().colorSchemeProperty().addListener((observable, previous, next) -> {
                if (theme.get() == Theme.SYSTEM) refreshEffectiveTheme();
            });
        } catch (RuntimeException ignored) {
            // Unit tests and very early startup can run before the toolkit owns a Preferences map.
        }
        PopupThemes.register(this);
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

    /** Applies tokens and the theme class to a scene root, and adopts the scene's window. */
    public void apply(Scene scene) {
        if (scene == null) return;
        apply(scene.getRoot());
        if (!scene.getStylesheets().contains(STYLESHEET)) scene.getStylesheets().add(STYLESHEET);
        if (scene.getWindow() != null) styledWindows.add(scene.getWindow());
        // A control can have been built, and its popup opened, before its scene reached us.
        PopupThemes.adoptOpen(this);
    }

    /** Applies tokens to a dialog pane or any other detached root. */
    public void apply(Parent root) {
        if (root == null) return;
        if (!root.getStylesheets().contains(STYLESHEET)) root.getStylesheets().add(STYLESHEET);
        styledRoots.add(root);
        if (root.getScene() != null && root.getScene().getWindow() != null) {
            styledWindows.add(root.getScene().getWindow());
        }
        if (setThemeClass(root)) restyleSubtree(root);
    }

    /** True when this manager's theme belongs to the window, directly or through its owner. */
    boolean owns(Window window) {
        if (window == null) return false;
        if (isStyledScene(window.getScene())) return true;
        Window owner = ownerOf(window);
        for (int depth = 0; owner != null && depth < 16; depth++) {
            if (styledWindows.contains(owner) || isStyledScene(owner.getScene())) return true;
            owner = ownerOf(owner);
        }
        return false;
    }

    /** Only the window kinds that can have an owner expose one; a plain Window cannot. */
    private static Window ownerOf(Window window) {
        if (window instanceof Stage stage) return stage.getOwner();
        if (window instanceof PopupWindow popup) return popup.getOwnerWindow();
        if (window instanceof PopupControl popup) return popup.getOwnerWindow();
        return null;
    }

    /** Puts the current tokens on one window's scene; the popup scene has none of its own. */
    void styleWindow(Window window) {
        Scene scene = window == null ? null : window.getScene();
        if (scene == null) return;
        if (!scene.getStylesheets().contains(STYLESHEET)) scene.getStylesheets().add(STYLESHEET);
        Parent root = scene.getRoot();
        if (root != null) {
            styledRoots.add(root);
            if (setThemeClass(root)) restyleSubtree(root);
        }
        styledWindows.add(window);
        // A popup's content is a separate problem — it sits behind JavaFX's CSSBridge and keeps the
        // palette it was built under — so that half belongs to the class that discovers popups.
        if (window instanceof PopupWindow || window instanceof PopupControl) {
            PopupThemes.restyle(this, window);
        }
    }

    /**
     * Marks the root's subtree for a new CSS pass. Moving the theme class alone re-resolves the
     * root's own properties, but a descendant that cached a looked-up token keeps the old value —
     * an open popup list stayed light — so the root's stylesheet list is re-set, which is the
     * public signal that invalidates everything below it.
     */
    static void restyleSubtree(Parent root) {
        // A popup scene carries the stylesheet itself; an application root carries it on the root,
        // and a detached dialog pane has not reached a scene yet. Whichever it is, the list that
        // actually holds the stylesheet is the one re-set: re-setting it is the public signal that
        // re-resolves every token below, and a root whose own list is empty would otherwise leave
        // its descendants holding the previous theme's colours.
        Scene scene = root.getScene();
        if (scene != null && scene.getRoot() == root
                && scene.getStylesheets().contains(STYLESHEET)) {
            scene.getStylesheets().setAll(new ArrayList<>(scene.getStylesheets()));
            // A showing popup scene does not take part in the CSS pulse the way an application
            // scene does, so the cleared values would stay cleared. A snapshot performs the
            // synchronised CSS-and-layout pass; a popup is small enough for that to be cheap.
            root.snapshot(new SnapshotParameters(), null);
            return;
        }
        if (root.getStylesheets().isEmpty()) return;
        root.getStylesheets().setAll(new ArrayList<>(root.getStylesheets()));
    }

    private boolean isStyledScene(Scene scene) {
        return scene != null && scene.getRoot() != null && styledRoots.contains(scene.getRoot());
    }

    /** Returns true when the root actually moved to the other theme class. */
    boolean setThemeClass(Parent root) {
        String target = isDark() ? DARK_CLASS : LIGHT_CLASS;
        if (root.getStyleClass().contains(target)) return false;
        root.getStyleClass().removeAll(LIGHT_CLASS, DARK_CLASS);
        root.getStyleClass().add(target);
        return true;
    }

    /**
     * Re-tokens everything this manager still has on screen. Popups included: a theme switch with
     * a menu open must not leave the menu in the old palette.
     */
    private void restyleOpenWindows() {
        for (Window window : new ArrayList<>(styledWindows)) {
            if (window.isShowing()) styleWindow(window);
        }
        for (Parent root : new ArrayList<>(styledRoots)) {
            Scene scene = root.getScene();
            if (scene == null || scene.getWindow() == null) {
                if (setThemeClass(root)) {
                    // A detached root cannot be restyled through a scene; its own subtree is
                    // re-marked so the next attach recomputes it.
                    root.applyCss();
                }
            }
        }
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
