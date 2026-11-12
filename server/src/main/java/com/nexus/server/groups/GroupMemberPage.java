package com.nexus.server.groups;

import com.nexus.server.messaging.ConversationMemberView;

import java.util.List;

public record GroupMemberPage(List<ConversationMemberView> items, String nextCursor) { }
