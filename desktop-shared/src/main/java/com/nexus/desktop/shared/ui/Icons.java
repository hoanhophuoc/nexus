package com.nexus.desktop.shared.ui;

import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * The one way Nexus draws an icon: a Feather {@link FontIcon} carrying the shared {@code nx-icon}
 * style class. Views do not put glyph characters in FXML or in resource bundles, and code does not
 * build a {@code FontIcon} by hand, so size and colour come from one CSS rule in both clients.
 *
 * <p>An icon is visual only. Every control that shows one still needs its own accessible text from
 * the i18n bundle, because a {@code FontIcon} exposes no words of its own.
 */
public final class Icons {
    /** Style class every Nexus icon carries; {@code theme.css} gives it a size and a colour. */
    public static final String STYLE_CLASS = "nx-icon";

    private Icons() {
    }

    public static FontIcon of(Feather icon) {
        FontIcon glyph = new FontIcon(icon);
        glyph.getStyleClass().add(STYLE_CLASS);
        return glyph;
    }

    /** Replaces the control's graphic; a null icon removes it. */
    public static void set(Labeled control, Feather icon) {
        control.setGraphic(icon == null ? null : of(icon));
    }

    /**
     * The theme toggle shows the theme in use: a moon while light (the action switches to dark), a
     * sun while dark (the action switches to light).
     */
    public static void themeToggle(Labeled control, boolean dark) {
        set(control, dark ? Feather.SUN : Feather.MOON);
    }

    /**
     * Names an icon-only control: the same words for assistive technology and for the hover tooltip,
     * because the icon itself carries no text. Safe to call again when the action changes.
     */
    public static void describe(Control control, String text) {
        control.setAccessibleText(text);
        if (control.getTooltip() == null) {
            control.setTooltip(new Tooltip(text));
        } else {
            control.getTooltip().setText(text);
        }
    }
}
