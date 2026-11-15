package com.nexus.desktop.admin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the link between the admin views and the two stylesheets this app loads: a style class that
 * neither defines renders as the JavaFX default, so hierarchy silently disappears. Only FXML is
 * scanned; classes a controller adds are covered by the FX tests that render those screens.
 */
class StyleClassContractTest {
    private static final Pattern STYLE_CLASS = Pattern.compile("styleClass=\"([^\"]*)\"");
    private static final Pattern DEFINED_CLASS = Pattern.compile("\\.([A-Za-z][A-Za-z0-9_-]*)");
    private static final List<String> STYLESHEETS = List.of(
            "/com/nexus/desktop/shared/ui/theme.css",
            "/com/nexus/desktop/admin/admin.css");

    @Test
    void everyStyleClassInTheViewsHasARuleInOneOfTheStylesheets() throws IOException, URISyntaxException {
        Set<String> defined = new LinkedHashSet<>();
        for (String stylesheet : STYLESHEETS) defined.addAll(definedClasses(stylesheet));
        Set<String> undefined = new LinkedHashSet<>();
        for (String view : viewSources()) {
            Matcher matcher = STYLE_CLASS.matcher(view);
            while (matcher.find()) {
                for (String token : matcher.group(1).trim().split("\\s+")) {
                    if (!token.isEmpty() && !defined.contains(token)) undefined.add(token);
                }
            }
        }
        assertThat(undefined).as("style classes used by the FXML views but defined in no stylesheet")
                .isEmpty();
    }

    private static Set<String> definedClasses(String stylesheet) throws IOException {
        try (InputStream input = StyleClassContractTest.class.getResourceAsStream(stylesheet)) {
            assertThat(input).as("stylesheet %s", stylesheet).isNotNull();
            Matcher matcher = DEFINED_CLASS.matcher(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
            Set<String> names = new LinkedHashSet<>();
            while (matcher.find()) names.add(matcher.group(1));
            return names;
        }
    }

    private static List<String> viewSources() throws IOException, URISyntaxException {
        URL directory = StyleClassContractTest.class.getResource("/com/nexus/desktop/admin/view");
        assertThat(directory).as("admin view resource directory").isNotNull();
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            return files.filter(file -> file.toString().endsWith(".fxml"))
                    .map(StyleClassContractTest::read)
                    .toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
