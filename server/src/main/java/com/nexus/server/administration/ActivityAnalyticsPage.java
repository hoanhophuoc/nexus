package com.nexus.server.administration;

import java.util.List;

public record ActivityAnalyticsPage(List<ActivityAnalytics> items, String nextCursor) {
}
