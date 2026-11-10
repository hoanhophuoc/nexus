package com.haichutieu.chatsystem.util;

import com.haichutieu.chatsystem.dto.Customer;
import com.haichutieu.chatsystem.dto.MessageConversation;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtilTest {

    @Test
    void shouldReturnNullWhenNoCompleteLineIsBuffered() {
        StringBuilder buffer = new StringBuilder("{\"partial\":");

        assertThat(Util.readLine(buffer)).isNull();
        assertThat(buffer).hasToString("{\"partial\":");
    }

    @Test
    void shouldSplitConcatenatedLinesInOrder() {
        StringBuilder buffer = new StringBuilder("first\nsecond\nthird");

        assertThat(Util.readLine(buffer)).isEqualTo("first");
        assertThat(Util.readLine(buffer)).isEqualTo("second");
        assertThat(Util.readLine(buffer)).isNull();
        assertThat(buffer).hasToString("third");
    }

    @Test
    void shouldReturnEmptyLineForConsecutiveNewlines() {
        StringBuilder buffer = new StringBuilder("\nvalue\n");

        assertThat(Util.readLine(buffer)).isEmpty();
        assertThat(Util.readLine(buffer)).isEqualTo("value");
    }

    @Test
    void shouldRoundTripUnicodeProtocolPayload() {
        String payload = "Xin chào 👋\nsecond line";
        String json = Util.serializeObject(List.of("name", "username", "email@example.com", payload));

        List<String> copy = Util.deserializeObject(json, new TypeReference<>() {
        });

        assertThat(copy).containsExactly("name", "username", "email@example.com", payload);
    }

    @Test
    void shouldSerializeTimestampsAsNumbersForTheWireProtocol() {
        Customer customer = customer();
        customer.setCreateDate(Timestamp.valueOf("2026-11-04 10:15:30"));
        customer.setBirthdate(java.sql.Date.valueOf("2000-02-29"));

        String json = Util.serializeObject(customer);
        Customer copy = Util.deserializeObject(json, new TypeReference<>() {
        });

        assertThat(json).doesNotContain("2026-11-04T");
        assertThat(copy.getUsername()).isEqualTo("alice1234");
        assertThat(copy.getEmail()).isEqualTo("alice@example.com");
        assertThat(copy.getCreateDate()).isEqualTo(customer.getCreateDate());
        assertThat(copy.getBirthdate()).isEqualTo(customer.getBirthdate());
    }

    @Test
    void shouldRoundTripPublicFieldProtocolDtos() {
        MessageConversation message = new MessageConversation(9L, 4L, 12, "Alice",
                Timestamp.valueOf("2026-11-04 11:00:00"), "hello");

        String json = Util.serializeObject(message);
        MessageConversation copy = Util.deserializeObject(json, new TypeReference<>() {
        });

        assertThat(copy.id).isEqualTo(9L);
        assertThat(copy.conversation_id).isEqualTo(4L);
        assertThat(copy.senderID).isEqualTo(12);
        assertThat(copy.senderName).isEqualTo("Alice");
        assertThat(copy.message).isEqualTo("hello");
        assertThat(copy.time).isEqualTo(message.time);
    }

    @Test
    void shouldRejectMalformedJson() {
        assertThatThrownBy(() -> Util.deserializeObject("{not json", new TypeReference<Customer>() {
        }))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
    }

    private static Customer customer() {
        Customer customer = new Customer();
        customer.setId(1);
        customer.setUsername("alice1234");
        customer.setPassword("$argon2id$hash");
        customer.setName("Alice");
        customer.setEmail("alice@example.com");
        customer.setCreateDate(Timestamp.valueOf("2026-01-01 00:00:00"));
        return customer;
    }
}
