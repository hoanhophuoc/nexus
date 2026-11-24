package com.nexus.desktop.shared.testing;

import com.nexus.desktop.shared.ui.Theme;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads and measures the colours a laid-out JavaFX skin actually painted.
 *
 * <p>The contrast suites deliberately read foregrounds and backgrounds from the rendered nodes
 * rather than from design tokens: a stylesheet regression has to fail there even when the token
 * pairs still pass. Both clients need the same reading rules — a region with a transparent fill
 * takes the nearest painted ancestor's colour, a label's colour is the fill of its rendered text
 * node, and a surface is pinned to its token as well as measured — so the rules live here once.</p>
 */
public final class RenderedContrast {
    /** The most a rendered surface may drift from its token, summed over its channels: 8-bit units. */
    private static final int RENDER_ROUNDING = 2;

    private RenderedContrast() {
    }

    /** The fill of the text node a label or cell rendered. */
    public static Color textFill(Node node) {
        assertThat(node).as("a rendered node").isNotNull();
        Node text = node.lookup(".text");
        if (text instanceof Text rendered && rendered.getFill() instanceof Color colour) {
            return colour;
        }
        if (node instanceof Labeled labeled && labeled.getTextFill() instanceof Color colour) {
            return colour;
        }
        throw new AssertionError("No rendered text fill on " + node);
    }

    /**
     * The colour a region paints, falling back to the nearest painted ancestor. A fill that is
     * transparent (or not a plain colour at all) is not a surface anyone can read against.
     */
    public static Color effectiveBackground(Region region, Color fallback) {
        assertThat(region).as("a rendered region").isNotNull();
        Background background = region.getBackground();
        if (background != null) {
            for (BackgroundFill fill : background.getFills()) {
                if (fill.getFill() instanceof Color colour && colour.getOpacity() >= 0.99) {
                    return colour;
                }
            }
        }
        Parent parent = region.getParent();
        while (parent instanceof Region parentRegion) {
            Color resolved = effectiveBackground(parentRegion, null);
            if (resolved != null) return resolved;
            parent = parentRegion.getParent();
        }
        return fallback;
    }

    /** Asserts the pair reaches {@code minimum} and reports the ratio, so a drifting pair is visible. */
    public static void measure(Theme theme, String label, Color foreground, Color background,
                               double minimum) {
        assertThat(foreground).as("%s %s foreground", theme, label).isNotNull();
        assertThat(background).as("%s %s background", theme, label).isNotNull();
        double ratio = contrast(foreground, background);
        System.out.printf("[contrast] %-5s %-34s %.2f:1%n", theme, label, ratio);
        assertThat(ratio).as("%s %s contrast %.2f:1", theme, label, ratio)
                .isGreaterThanOrEqualTo(minimum);
    }

    /**
     * Pins a surface to the token it is supposed to carry, within font/render rounding.
     *
     * <p>Compared in 8-bit channel units rather than in doubles, summed over the four channels the
     * way the earlier epsilon was: a skin may round each channel by a fraction of a unit without the
     * surface drifting away from its token. Opacity is compared along with the colour, since a
     * surface that lost its alpha is not that token either.
     */
    public static void assertEqualsColor(String label, Color actual, Color expected) {
        assertThat(actual).as(label)
                .usingComparator(RenderedContrast::channelDistance)
                .isEqualTo(expected);
    }

    /** 0 when the two colours' four channels differ by no more than rounding in total. */
    private static int channelDistance(Color first, Color second) {
        if (first == null) return second == null ? 0 : 1;
        if (second == null) return 1;
        int delta = Math.abs(channelOf(first, Color::getRed) - channelOf(second, Color::getRed))
                + Math.abs(channelOf(first, Color::getGreen) - channelOf(second, Color::getGreen))
                + Math.abs(channelOf(first, Color::getBlue) - channelOf(second, Color::getBlue))
                + Math.abs(channelOf(first, Color::getOpacity) - channelOf(second, Color::getOpacity));
        return delta <= RENDER_ROUNDING ? 0 : 1;
    }

    /** A colour channel in 8-bit units, the precision a rendered surface can actually carry. */
    private static int channelOf(Color colour, ToDoubleFunction<Color> channel) {
        return (int) Math.round(channel.applyAsDouble(colour) * 255);
    }

    private static double contrast(Color first, Color second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color colour) {
        return 0.2126 * linearise(colour.getRed())
                + 0.7152 * linearise(colour.getGreen())
                + 0.0722 * linearise(colour.getBlue());
    }

    /** One sRGB channel expanded to linear light, where luminance is additive. */
    private static double linearise(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
