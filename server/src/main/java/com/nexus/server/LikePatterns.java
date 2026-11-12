package com.nexus.server;

import java.util.Locale;

/**
 * Builds the pattern for a case-insensitive substring search over a normalized column.
 *
 * <p>The wildcards are escaped so a term containing {@code %} or {@code _} matches those characters
 * literally instead of widening the search to every row. Callers pass the pattern to
 * {@code LIKE ? ESCAPE '\'} against a trigram index.
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    public static String containing(String term) {
        String escaped = term.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
