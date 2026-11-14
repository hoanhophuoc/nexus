package com.nexus.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Operator command over the abandoned outbox rows, run instead of the scheduler:
 * {@code --chat.messaging.outbox-admin=list}, {@code =requeue}, or
 * {@code =requeue:<event-id>[,<event-id>]}. It prints its result and shuts the application down, so
 * a deployment does not leave a second publisher polling the same table.
 */
@Component
public class OutboxAdminCommand implements ApplicationRunner {
    static final int LIST_LIMIT = 50;
    private static final Logger log = LoggerFactory.getLogger(OutboxAdminCommand.class);

    private final OutboxAdminService admin;
    private final ConfigurableApplicationContext context;
    private final String command;

    OutboxAdminCommand(OutboxAdminService admin, ConfigurableApplicationContext context,
                       @Value("${chat.messaging.outbox-admin:}") String command) {
        this.admin = admin;
        this.context = context;
        this.command = command;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (command.isBlank()) return;
        try {
            log.info("{}", execute(command, admin));
        } finally {
            SpringApplication.exit(context, () -> 0);
        }
    }

    static String execute(String command, OutboxAdminService admin) {
        return switch (command) {
            case "list" -> describe(admin.abandoned(LIST_LIMIT));
            case "requeue" -> "Requeued " + admin.requeueAll() + " abandoned event(s).";
            default -> requeue(command, admin);
        };
    }

    private static String requeue(String command, OutboxAdminService admin) {
        if (!command.startsWith("requeue:")) {
            throw new IllegalArgumentException("Unknown outbox admin command \"" + command
                    + "\"; use list, requeue or requeue:<event-id>[,<event-id>].");
        }
        List<UUID> eventIds = Arrays.stream(command.substring("requeue:".length()).split(","))
                .map(String::trim).filter(id -> !id.isEmpty()).map(UUID::fromString).toList();
        return "Requeued " + admin.requeue(eventIds) + " of " + eventIds.size() + " event(s).";
    }

    private static String describe(List<OutboxAdminService.AbandonedEvent> events) {
        if (events.isEmpty()) return "No abandoned outbox events.";
        StringBuilder report = new StringBuilder(events.size() + " abandoned outbox event(s):");
        for (OutboxAdminService.AbandonedEvent event : events) {
            report.append(String.format("%n  %s %s at %s, %s: %s", event.eventId(),
                    event.eventType(), event.abandonedAt(), event.reason(), event.lastError()));
        }
        return report.toString();
    }
}
