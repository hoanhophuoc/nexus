package com.nexus.server.administration;

import java.util.List;

public record FriendAnalyticsPage(List<FriendAnalytics> items, String nextCursor) {
}
