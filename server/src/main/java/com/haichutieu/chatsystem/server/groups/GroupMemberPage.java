package com.haichutieu.chatsystem.server.groups;

import com.haichutieu.chatsystem.server.messaging.ConversationMemberView;

import java.util.List;

public record GroupMemberPage(List<ConversationMemberView> items, String nextCursor) { }
