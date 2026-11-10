package com.haichutieu.chatsystem.desktop.shared.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** WCAG 2.2 AA checks for the semantic colour tokens used by both desktops. */
class ThemeContrastTest {
    private static final String STYLESHEET =
            "/com/haichutieu/chatsystem/desktop/shared/ui/theme.css";

    @Test
    void lightThemeMeetsTextAndNonTextContrast() throws IOException {
        assertTheme("light", ".root");
    }

    @Test
    void darkThemeMeetsTextAndNonTextContrast() throws IOException {
        assertTheme("dark", ".theme-dark");
    }

    private static void assertTheme(String name, String selector) throws IOException {
        Map<String, int[]> tokens = tokens(selector);
        for (String[] pair : new String[][]{
                {"text", "bg"},
                {"text", "surface"},
                {"text-muted", "bg"},
                {"text-muted", "surface"},
                {"text-muted", "surface-muted"},
                {"text-subtle", "bg"},
                {"text-subtle", "surface"},
                {"text-subtle", "surface-muted"},
                {"accent", "surface"},
                {"accent", "accent-soft"},
                {"on-accent", "accent"},
                {"danger", "danger-soft"},
                {"danger", "surface"},
                {"bubble-mine-text", "bubble-mine"},
                {"focus-ring", "surface"}
        }) {
            assertContrast(name, tokens, pair[0], pair[1], 4.5);
        }
        for (String[] pair : new String[][]{
                {"border-strong", "surface"},
                {"border-strong", "surface-muted"},
                {"focus-ring", "surface"}
        }) {
            assertContrast(name, tokens, pair[0], pair[1], 3.0);
        }
    }

    private static void assertContrast(String theme, Map<String, int[]> tokens,
                                       String foreground, String background, double minimum) {
        assertThat(tokens).as("%s token %s", theme, foreground).containsKey(foreground);
        assertThat(tokens).as("%s token %s", theme, background).containsKey(background);
        double ratio = contrast(tokens.get(foreground), tokens.get(background));
        assertThat(ratio)
                .as("%s %s on %s contrast", theme, foreground, background)
                .isGreaterThanOrEqualTo(minimum);
    }

    private static Map<String, int[]> tokens(String selector) throws IOException {
        String css;
        try (InputStream stream = ThemeContrastTest.class.getResourceAsStream(STYLESHEET)) {
            assertThat(stream).as("theme stylesheet resource").isNotNull();
            css = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher block = Pattern.compile(Pattern.quote(selector) + "\\s*\\{([^}]*)}")
                .matcher(css);
        assertThat(block.find()).as("CSS block %s", selector).isTrue();
        Matcher token = Pattern.compile(
                "-color-([a-z-]+):\\s*rgb\\(\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\s*\\)")
                .matcher(block.group(1));
        Map<String, int[]> values = new LinkedHashMap<>();
        while (token.find()) {
            values.put(token.group(1), new int[]{
                    Integer.parseInt(token.group(2)),
                    Integer.parseInt(token.group(3)),
                    Integer.parseInt(token.group(4))
            });
        }
        return values;
    }

    private static double contrast(int[] first, int[] second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(int[] colour) {
        return 0.2126 * channel(colour[0])
                + 0.7152 * channel(colour[1])
                + 0.0722 * channel(colour[2]);
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.04045
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
}
