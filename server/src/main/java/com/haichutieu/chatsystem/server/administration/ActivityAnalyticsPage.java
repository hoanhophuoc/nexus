package com.haichutieu.chatsystem.server.administration;

import java.util.List;

public record ActivityAnalyticsPage(List<ActivityAnalytics> items, String nextCursor) {
}
