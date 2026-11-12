package com.nexus.desktop.admin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Static contract for the admin UI: every used string is localized, styled with tokens only. */
class AdminResourceContractTest {
    private static final Pattern FXML_KEY = Pattern.compile("%([A-Za-z0-9_.-]+)");
    private static final Pattern JAVA_KEY = Pattern.compile("\"(admin\\.[A-Za-z0-9_.-]+)\"");
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}");
    private static final Pattern INLINE_STYLE = Pattern.compile("\\bstyle\\s*=\\s*\"");
    private static final Pattern FXML_LITERAL_TEXT = Pattern.compile(
            "\\b(?:text|promptText|accessibleText)\\s*=\\s*\"(?!%)");
    private static final Pattern JAVA_UI_LITERAL = Pattern.compile(
            "\\b(?:new Button|new Label|setText|setPromptText|setAccessibleText)\\s*\\(\\s*\"[^\"\\s]");

    @Test
    void everyUsedKeyExistsInEnglishAndVietnamese() throws IOException {
        Path root = projectRoot();
        Set<String> used = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("src/main"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (name.endsWith(".fxml")) {
                    collect(FXML_KEY, text, used);
                } else if (name.endsWith(".java")) {
                    collect(JAVA_KEY, text, used);
                }
            }
        }
        used.removeIf(key -> key.endsWith(".css") || key.endsWith(".fxml"));
        Properties english = bundle(root, "messages.properties");
        Properties vietnamese = bundle(root, "messages_vi.properties");
        assertThat(used).as("resource keys referenced by admin FXML/Java").isNotEmpty();
        for (String key : used) {
            assertThat(english.getProperty(key)).as("English bundle key %s", key).isNotBlank();
            assertThat(vietnamese.getProperty(key)).as("Vietnamese bundle key %s", key).isNotBlank();
        }
    }

    @Test
    void fxmlHasNoInlineStyleOrHardcodedUiText() throws IOException {
        Path views = projectRoot().resolve("src/main/resources/com/nexus/desktop/admin/view");
        try (Stream<Path> files = Files.list(views)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".fxml")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(INLINE_STYLE.matcher(text).find()).as("inline style in %s", file).isFalse();
                assertThat(FXML_LITERAL_TEXT.matcher(text).find()).as("hardcoded text in %s", file).isFalse();
            }
        }
    }

    @Test
    void cssUsesOnlyThemeTokensAndJavaHasNoUiLiterals() throws IOException {
        Path root = projectRoot();
        Path css = root.resolve("src/main/resources/com/nexus/desktop/admin/admin.css");
        assertThat(HEX_COLOR.matcher(Files.readString(css, StandardCharsets.UTF_8)).find())
                .as("admin.css must use shared theme tokens, not hex colours").isFalse();
        try (Stream<Path> files = Files.walk(root.resolve("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(JAVA_UI_LITERAL.matcher(text).find())
                        .as("hardcoded UI literal in %s", file).isFalse();
            }
        }
    }

    private static Properties bundle(Path root, String name) throws IOException {
        Path file = root.resolve("src/main/resources/com/nexus/desktop/admin/i18n").resolve(name);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void collect(Pattern pattern, String text, Set<String> keys) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) keys.add(matcher.group(1));
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isDirectory(current.resolve("src/main"))) return current;
        Path nested = current.resolve("desktop-admin");
        if (Files.isDirectory(nested.resolve("src/main"))) return nested;
        throw new IllegalStateException("Cannot locate desktop-admin sources from " + current);
    }
}
