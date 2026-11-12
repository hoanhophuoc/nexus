package com.nexus.server.infrastructure;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.MaintNotificationsConfig;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisClientConfiguration {
    /**
     * Maintenance notifications are a Redis Enterprise/Cloud feature: OSS Redis replies
     * {@code ERR unknown subcommand 'MAINT_NOTIFICATIONS'}, which Lettuce surfaces as an
     * exception on every RESP3 connection handshake. We do not run against a cluster that
     * migrates slots, so the client opts out instead of probing.
     */
    @Bean
    LettuceClientOptionsBuilderCustomizer disableMaintenanceNotifications() {
        return (ClientOptions.Builder builder) ->
                builder.maintNotificationsConfig(MaintNotificationsConfig.disabled());
    }
}
