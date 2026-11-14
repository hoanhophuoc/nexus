package com.nexus.desktop.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenGenerationTest {

    @Test
    void ignoresACompletionFromAnEarlierOpen() {
        OpenGeneration open = new OpenGeneration();
        int first = open.open();
        int second = open.open();

        assertThat(open.isCurrent(first)).as("the conversation it was started for is closed").isFalse();
        assertThat(open.isCurrent(second)).as("the conversation open now is current").isTrue();
    }

    @Test
    void everyOpenGetsItsOwnToken() {
        OpenGeneration open = new OpenGeneration();

        assertThat(open.open()).isNotEqualTo(open.open());
    }
}
