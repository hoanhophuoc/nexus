package com.nexus.desktop.shared.ui;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The theme is owned in exactly one place. A second manager, or a module that installs its own
 * user-agent stylesheet, gives the two clients two different answers to "which theme is active" —
 * and the failure is silent, so it is checked here instead of being left to review.
 *
 * <p>The check reads the production sources of the three desktop modules: no source outside
 * {@link ThemeManager} may call {@code setUserAgentStylesheet}, and no other class may be called
 * {@code ThemeManager}.
 */
class ThemeOwnershipTest {

    @Test
    void noProductionModuleTakesOverTheUserAgentStylesheet() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String module : List.of("desktop-shared", "desktop-user", "desktop-admin")) {
            for (Path source : javaSources(module)) {
                if (Files.readString(source).contains("setUserAgentStylesheet")) {
                    offenders.add(relative(source));
                }
            }
        }
        assertThat(offenders)
                .as("production code that installs its own user-agent stylesheet instead of "
                        + "letting ThemeManager own the theme")
                .isEmpty();
    }

    @Test
    void theOnlyThemeManagerLivesInDesktopShared() throws IOException {
        List<String> managers = new ArrayList<>();
        for (String module : List.of("desktop-shared", "desktop-user", "desktop-admin")) {
            try (Stream<Path> files = Files.walk(moduleRoot(module))) {
                files.filter(path -> path.getFileName().toString().equals("ThemeManager.java"))
                        .forEach(path -> managers.add(relative(path)));
            }
        }
        assertThat(managers).containsExactly(
                "desktop-shared/src/main/java/com/nexus/desktop/shared/ui/ThemeManager.java");
    }

    private static List<Path> javaSources(String module) throws IOException {
        try (Stream<Path> files = Files.walk(moduleRoot(module))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static Path moduleRoot(String module) {
        return repositoryRoot().resolve(module).resolve("src/main/java");
    }

    private static String relative(Path path) {
        return repositoryRoot().relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate the Nexus build from " + Path.of("").toAbsolutePath());
    }
}
