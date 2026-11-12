package com.nexus.server.messaging;

import java.util.List;

public record ConversationPage(List<ConversationView> items, String nextCursor) {
}
