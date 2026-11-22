package com.nexus.desktop.shared.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Carries the active Nexus theme into the separate scenes of popup windows.
 *
 * <p>A {@code ComboBox} popup, a menu, a tooltip and a {@code Dialog} each own a scene of their
 * own, and JavaFX gives that scene neither the application stylesheet nor the theme class, so the
 * semantic tokens never reach it. One listener on the public {@code Window.getWindows()} list
 * catches every window the toolkit opens, including ones created later, and hands it to the
 * manager that owns it: only a window whose owner chain reaches a window that manager already
 * styled is touched, so two applications — or two tests in one JVM — never restyle each other.
 * Managers are held weakly, so this class never keeps one alive.
 */
final class PopupThemes {
    private static final List<WeakReference<ThemeManager>> MANAGERS = new ArrayList<>();
    private static final Set<Window> WATCHED = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean listening;

    private PopupThemes() {
    }

    static synchronized void register(ThemeManager manager) {
        MANAGERS.removeIf(reference -> {
            ThemeManager registered = reference.get();
            return registered == null || registered == manager;
        });
        MANAGERS.add(new WeakReference<>(manager));
        if (listening) return;
        listening = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (!change.wasAdded()) continue;
                for (Window window : change.getAddedSubList()) watch(window);
            }
        });
    }

    /** Styles the popups a manager's window already has open, for one opened before a scene was. */
    static void adoptOpen(ThemeManager manager) {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (manager.owns(window)) manager.styleWindow(window);
        }
    }

    private static void watch(Window window) {
        synchronized (PopupThemes.class) {
            if (!WATCHED.add(window)) return;
        }
        // A content-only popup (a tooltip, a context menu) builds its scene inside show(), which
        // can be after the window joined the list, so the showing edge is where the scene is
        // guaranteed to exist.
        window.showingProperty().addListener((observable, previous, showing) -> {
            if (Boolean.TRUE.equals(showing)) style(window);
        });
        style(window);
    }

    private static void style(Window window) {
        for (ThemeManager manager : managers()) {
            if (manager.owns(window)) manager.styleWindow(window);
        }
    }

    /**
     * Gives a popup scene what an application scene gets for free.
     *
     * <p>A popup's content hangs off its scene root through JavaFX's CSSBridge, and the colours the
     * list, menu or tooltip resolves are looked up through that bridge, so the class on the popup
     * root alone does not reach them: a content node keeps the palette it was built under, which
     * shows up as a white list under a dark popup or a tooltip that stays light after a reopen.
     * Repeating the class on every content node makes the tokens local, and the pass runs even when
     * the class was already right, because a popup shown again reuses it and the colours must be
     * resolved after the class is in place.
     */
    static void restyle(ThemeManager manager, Window window) {
        Scene scene = window == null ? null : window.getScene();
        Parent root = scene == null ? null : scene.getRoot();
        if (root == null) return;
        stamp(manager, root);
        ThemeManager.restyleSubtree(root);
    }

    private static void stamp(ThemeManager manager, Parent parent) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Parent content) {
                manager.setThemeClass(content);
                stamp(manager, content);
            }
        }
    }

    private static List<ThemeManager> managers() {
        List<ThemeManager> live = new ArrayList<>();
        synchronized (PopupThemes.class) {
            MANAGERS.removeIf(reference -> reference.get() == null);
            for (WeakReference<ThemeManager> reference : MANAGERS) {
                ThemeManager manager = reference.get();
                if (manager != null) live.add(manager);
            }
        }
        return live;
    }
}
