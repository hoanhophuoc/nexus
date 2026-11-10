package com.haichutieu.chatsystem.desktop.shared;

import org.junit.jupiter.api.Test;

import java.net.HttpCookie;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionApiTest {
    @Test
    void buildsSecureWebSocketUriAndKeepsCookiesPerSession() {
        var first = new SessionApi(URI.create("https://chat.example.test"));
        var second = new SessionApi(URI.create("https://chat.example.test"));
        var endpoint = URI.create("https://chat.example.test/api/v1");
        first.cookies().getCookieStore().add(endpoint, new HttpCookie("CHAT_SESSION", "opaque"));

        assertThat(first.webSocketUri()).isEqualTo(URI.create("wss://chat.example.test/ws/v1"));
        assertThat(first.cookies().getCookieStore().getCookies()).hasSize(1);
        assertThat(second.cookies().getCookieStore().getCookies()).isEmpty();
        first.clear();
        assertThat(first.cookies().getCookieStore().getCookies()).isEmpty();
    }

    @Test
    void usesAnExplicitRealtimeEndpointWhenConfigured() {
        var session = new SessionApi(URI.create("https://chat.example.test"),
                URI.create("wss://realtime.example.test/ws/v1"));

        assertThat(session.webSocketUri()).isEqualTo(URI.create("wss://realtime.example.test/ws/v1"));
        assertThatThrownBy(() -> new SessionApi(URI.create("https://chat.example.test"),
                URI.create("https://realtime.example.test/ws/v1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonOriginApiUrls() {
        assertThatThrownBy(() -> new SessionApi(URI.create("https://user:secret@chat.example.test/api")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionApi(URI.create("ws://chat.example.test")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
