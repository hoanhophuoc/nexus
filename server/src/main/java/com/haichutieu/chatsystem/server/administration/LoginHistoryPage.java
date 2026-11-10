package com.haichutieu.chatsystem.server.administration;

import java.util.List;

public record LoginHistoryPage(List<LoginHistoryEntry> items, String nextCursor) {
}
