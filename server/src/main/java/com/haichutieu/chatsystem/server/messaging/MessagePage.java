package com.haichutieu.chatsystem.server.messaging;

import java.util.List;

public record MessagePage(List<MessageView> items, String nextCursor) {
}
