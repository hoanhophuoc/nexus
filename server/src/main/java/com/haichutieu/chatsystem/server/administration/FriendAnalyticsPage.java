package com.haichutieu.chatsystem.server.administration;

import java.util.List;

public record FriendAnalyticsPage(List<FriendAnalytics> items, String nextCursor) {
}
