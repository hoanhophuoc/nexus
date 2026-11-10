package com.haichutieu.chatsystem.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTest {

    @Test
    void shouldLoadPackagedDefaults() {
        Properties properties = Config.load("server-config.properties", "missing-config.properties", Map.of());

        assertThat(properties.getProperty("server.port")).isEqualTo("8080");
        assertThat(properties.getProperty("hibernate.hikari.dataSource.url")).isEqualTo("jdbc:postgresql://localhost:5432/chat_system");
    }

    @Test
    void shouldLetWorkingDirectoryFileOverridePackagedDefaults(@TempDir Path directory) throws IOException {
        Path override = directory.resolve("server-config.properties");
        Files.writeString(override, "server.port=9999\nserver.host=127.0.0.1\n");

        Properties properties = Config.load("server-config.properties", override.toString(), Map.of());

        assertThat(properties.getProperty("server.port")).isEqualTo("9999");
        assertThat(properties.getProperty("server.host")).isEqualTo("127.0.0.1");
    }

    @Test
    void shouldIgnoreUnsetEnvironmentVariable(@TempDir Path directory) throws IOException {
        Path override = directory.resolve("client-config.properties");
        Files.writeString(override, "client.port=9998\n");

        Properties properties = Config.load("client-config.properties", override.toString(),
                Map.of("client.port", "CHAT_TEST_UNSET_ENV_VAR"));

        assertThat(properties.getProperty("client.port")).isEqualTo("9998");
    }

    @Test
    void shouldReturnEmptyPropertiesWhenPackagedResourceIsMissing() {
        Properties properties = Config.load("does-not-exist.properties", "missing.properties", Map.of());

        assertThat(properties).isEmpty();
    }
}
