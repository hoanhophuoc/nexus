package com.haichutieu.chatsystem.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Loads configuration with a predictable precedence:
 * <ol>
 *     <li>the defaults packaged on the classpath;</li>
 *     <li>an optional properties file in the working directory (same file name);</li>
 *     <li>environment variables, which always win.</li>
 * </ol>
 */
public final class Config {
    private Config() {
    }

    /**
     * @param resourcePath packaged defaults, e.g. {@code server-config.properties}
     * @param fileName     optional file in the working directory used to override the defaults
     * @param envOverrides property key to environment variable name
     */
    public static Properties load(String resourcePath, String fileName, Map<String, String> envOverrides) {
        Properties properties = new Properties();

        try (InputStream input = Config.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read packaged configuration " + resourcePath, e);
        }

        Path externalFile = Path.of(fileName);
        if (Files.isRegularFile(externalFile)) {
            try (Reader reader = Files.newBufferedReader(externalFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read configuration file " + externalFile.toAbsolutePath(), e);
            }
        }

        envOverrides.forEach((key, envName) -> {
            String value = System.getenv(envName);
            if (value != null && !value.isBlank()) {
                properties.setProperty(key, value);
            }
        });

        return properties;
    }
}
