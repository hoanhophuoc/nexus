package com.nexus.server.messaging;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherTest {

    @Test
    void namesThreeAbandonedIdsAndCountsTheRest() {
        List<UUID> abandoned = IntStream.rangeClosed(1, 100).mapToObj(OutboxPublisherTest::id).toList();

        assertThat(OutboxPublisher.describe(abandoned))
                .as("an abandoned batch is up to 100 rows, so the line stays short")
                .isEqualTo(id(1) + ", " + id(2) + ", " + id(3) + " and 97 more");
    }

    @Test
    void listsEveryIdWhenTheyAllFitOnTheLine() {
        assertThat(OutboxPublisher.describe(List.of(id(7), id(8))))
                .isEqualTo(id(7) + ", " + id(8));
    }

    private static UUID id(int number) {
        return new UUID(0L, number);
    }
}
