package com.haichutieu.chatsystem.server.administration;

public record ActivityAnalytics(AdminUserView user, int appOpens, int peopleChattedWith, int groupsChattedWith) {
}
