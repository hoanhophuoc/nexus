package com.nexus.server.administration;

public record ActivityAnalytics(AdminUserView user, int appOpens, int peopleChattedWith, int groupsChattedWith) {
}
